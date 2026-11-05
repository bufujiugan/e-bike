package com.bike.server.pi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.bike.server.auth.UserAuthRecord;
import com.bike.server.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class PiWorkflowServiceTest {
    private final PiMapper pis=mock(PiMapper.class);
    private final PiWorkflowMapper mapper=mock(PiWorkflowMapper.class);
    private final OperationLogMapper logs=mock(OperationLogMapper.class);
    private final PiBusinessService business=mock(PiBusinessService.class);
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final PiWorkflowService service=CustomerScopeTestSupport.isolated(new PiWorkflowServiceImpl(pis,mapper,logs,json));
    private PiView pi;
    @BeforeEach void setup() throws Exception {
        var user=new UserAuthRecord(1L,"workflow-test","unused","INTERNAL",null,null,true);
        var authentication = new UsernamePasswordAuthenticationToken(user.username(),null,List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        authentication.setDetails(user);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        ReflectionTestUtils.setField(service,"business",business);
        pi=new PiView();pi.setId(1L);pi.setSeriesId(1L);pi.setCustomerId(10L);pi.setStatus("CONFIRMED");pi.setPaidAmount(BigDecimal.ZERO);pi.setShipmentRequiredAmount(new BigDecimal("100"));
        pi.setDepositRequiredAmount(new BigDecimal("50"));
        when(pis.selectById(1L)).thenReturn(pi);when(pis.selectForUpdate(1L)).thenReturn(pi);
        when(business.document(1L)).thenReturn(json.readValue("{\"business\":{\"warehouseId\":1,\"domesticShipment\":true,\"collectionEntity\":\"GUANGTA\"}}",PiDocumentSnapshot.class));
        when(logs.insert(any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(1);
        when(mapper.complete(anyLong(),anyInt(),any(),any(),any())).thenReturn(1);
    }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
    @Test void documentsPrecedePaymentAndProductionIsOptional() {
        var view=service.get(1L);
        assertThat(view.currentStep()).isEqualTo("DOCUMENTS_READY");
        assertThat(view.steps()).extracting(PiWorkflowStepView::code).containsExactly("PI_CREATED","DOCUMENTS_READY","PAYMENT_CONFIRMED","INVENTORY_RESERVED","PURCHASE_CONTRACT_READY","STOCK_DISPATCHED","FORWARDER_SELECTED","DOMESTIC_EXPRESS_BOOKED","WAREHOUSE_ARRIVED","TRACKING_RECORDED","CUSTOMER_RECEIVED");
    }
    @Test void partialPaymentCannotCompletePaymentStepEvenWithOldStoredCompletion() {
        pi.setPaymentStatus("PARTIAL");pi.setPaidAmount(BigDecimal.ONE);
        when(mapper.selectSteps(1L)).thenReturn(rows("DOCUMENTS_READY","PAYMENT_CONFIRMED"));
        var view=service.get(1L);assertThat(view.currentStep()).isEqualTo("PAYMENT_CONFIRMED");
    }
    @Test void recordsDocumentsBeforeAnyReceipt() {
        var request=new PiWorkflowRequest("已核对","SALE-1",true,true,false,null,null,null,null,null,null,null,null,null,null,null);
        service.complete(1L,"DOCUMENTS_READY",request);
        verify(mapper).complete(eq(1L),eq(3),any(),eq("已核对"),contains("SALE-1"));
    }
    @Test void domesticPurchaseContractCanBeRecordedBeforePayment() throws Exception {
        when(mapper.selectStepsForUpdate(1L)).thenReturn(rows("DOCUMENTS_READY"));
        var request=json.readValue("{\"purchaseContractParty\":\"SAVA\",\"purchaseContractNo\":\"BUY-1\"}",PiWorkflowRequest.class);
        service.complete(1L,"PURCHASE_CONTRACT_READY",request);
        verify(mapper).complete(eq(1L),eq(10),any(),any(),contains("BUY-1"));
    }
    @Test void foreignWarehouseDoesNotRequireDomesticPurchaseContract() throws Exception {
        when(business.document(1L)).thenReturn(json.readValue("{\"business\":{\"warehouseId\":2,\"domesticShipment\":false,\"collectionEntity\":\"HONG_KONG\"}}",PiDocumentSnapshot.class));
        assertThat(service.get(1L).steps().stream().filter(s->s.code().equals("PURCHASE_CONTRACT_READY")).findFirst().orElseThrow().status()).isEqualTo("COMPLETED");
    }
    @Test void stockCannotBeMarkedDispatchedByClickingComplete() {
        paid();when(mapper.selectStepsForUpdate(1L)).thenReturn(rows("DOCUMENTS_READY","PURCHASE_CONTRACT_READY"));
        assertThatThrownBy(()->service.complete(1L,"STOCK_DISPATCHED",new PiWorkflowRequest("确认"))).isInstanceOf(BusinessException.class).hasMessageContaining("真实业务记录");
    }
    @Test void customerForwarderCanRecordUnknownDomesticCarrierWithExplanation() throws Exception {
        paid();when(business.allDispatched(1L)).thenReturn(true);
        when(mapper.selectStepsForUpdate(1L)).thenReturn(rows("DOCUMENTS_READY","PURCHASE_CONTRACT_READY","FORWARDER_SELECTED"));
        service.complete(1L,"DOMESTIC_EXPRESS_BOOKED",new PiWorkflowRequest("客户自行安排送仓，暂无单号"));
        verify(mapper).complete(eq(1L),eq(6),any(),contains("客户自行安排"),any());
    }
    @Test void sellerForwarderRequiresCarrierTrackingAndPrice() throws Exception {
        paid();when(business.allDispatched(1L)).thenReturn(true);
        var rows=new ArrayList<>(rows("DOCUMENTS_READY","PURCHASE_CONTRACT_READY","FORWARDER_SELECTED"));
        rows.set(2,row("FORWARDER_SELECTED",forwarderJson("SELLER")));
        when(mapper.selectStepsForUpdate(1L)).thenReturn(rows);
        assertThatThrownBy(()->service.complete(1L,"DOMESTIC_EXPRESS_BOOKED",new PiWorkflowRequest("待安排"))).hasMessageContaining("承运商、单号及运费");
    }
    @Test void receiptRequiresAllBatchesAndExplicitDate() {
        paid();when(business.allDispatched(1L)).thenReturn(true);when(business.allTracked(1L)).thenReturn(true);
        when(mapper.selectStepsForUpdate(1L)).thenReturn(rows("DOCUMENTS_READY","PURCHASE_CONTRACT_READY","FORWARDER_SELECTED","DOMESTIC_EXPRESS_BOOKED","WAREHOUSE_ARRIVED","TRACKING_RECORDED"));
        assertThatThrownBy(()->service.complete(1L,"CUSTOMER_RECEIVED",new PiWorkflowRequest("收到"))).hasMessageContaining("实际日期");
        when(business.allTracked(1L)).thenReturn(false);
        assertThat(service.get(1L).steps().stream().filter(s->s.code().equals("CUSTOMER_RECEIVED")).findFirst().orElseThrow().status()).isEqualTo("PENDING");
    }
    @Test void inventoryRetryUsesActualPaymentAndDoesNotStoreArtificialCompletion() {
        paid();when(business.hasReservedStock(pi)).thenReturn(false);when(business.reserveAfterPayment(pi,pi.getPaidAmount())).thenReturn(true);
        when(mapper.selectStepsForUpdate(1L)).thenReturn(rows("DOCUMENTS_READY"));
        service.complete(1L,"INVENTORY_RESERVED",new PiWorkflowRequest(null));
        verify(business).reserveAfterPayment(pi,new BigDecimal("100"));verify(mapper,never()).complete(eq(1L),eq(9),any(),any(),any());
    }
    @Test void revisionInheritsPurchaseContractButNeverOldReceipt() {
        pi.setPreviousRevisionId(2L);
        var previous = new PiView(); previous.setId(2L);
        when(pis.selectById(2L)).thenReturn(previous);
        when(mapper.selectSteps(1L)).thenReturn(List.of(new PiWorkflowStepRecord(10,"PURCHASE_CONTRACT_READY","合同","PENDING",null,null,null,null)));
        when(mapper.selectSteps(2L)).thenReturn(rows("DOCUMENTS_READY","PURCHASE_CONTRACT_READY","CUSTOMER_RECEIVED"));
        when(business.allTracked(1L)).thenReturn(true);
        var view = service.get(1L);
        assertThat(view.steps().stream().filter(s->s.code().equals("PURCHASE_CONTRACT_READY")).findFirst().orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(view.steps().stream().filter(s->s.code().equals("CUSTOMER_RECEIVED")).findFirst().orElseThrow().status()).isEqualTo("PENDING");
        assertThat(view.currentStep()).isEqualTo("DOCUMENTS_READY");
    }
    @Test void agentCannotCompleteInternalSteps() {
        var agent = new UserAuthRecord(2L,"agent","unused","WEB_AGENT",3L,null,true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(agent,null,List.of(new SimpleGrantedAuthority("ROLE_WEB_AGENT"),new SimpleGrantedAuthority("PI_WRITE"))));
        assertThatThrownBy(()->service.complete(1L,"DOCUMENTS_READY",new PiWorkflowRequest("x"))).isInstanceOf(BusinessException.class);
        verify(mapper,never()).complete(anyLong(),anyInt(),any(),any(),any());
    }
    @Test void productionUpdateRemainsOptionalAndAudited() {
        when(mapper.insertUpdate(anyLong(),any(),any(),any(),any())).thenReturn(1);
        service.recordProductionUpdate(1L,new PiWorkflowRequest("跟进",null,null,null,null,"正在生产",null,"材料齐套",null,null,null,null,null,null,null,false));
        verify(mapper).insertUpdate(eq(1L),eq("PRODUCTION_FOLLOW_UP"),any(),eq("跟进"),any());
    }
    private void paid(){pi.setPaidAmount(new BigDecimal("100"));when(business.hasReservedStock(pi)).thenReturn(true);}
    private List<PiWorkflowStepRecord> rows(String... codes){return Arrays.stream(codes).map(c->row(c,c.equals("DOCUMENTS_READY")?jsonDocuments():c.equals("PURCHASE_CONTRACT_READY")?"{\"purchaseContractParty\":\"SAVA\",\"purchaseContractNo\":\"BUY-1\"}":c.equals("FORWARDER_SELECTED")?forwarderJson("CUSTOMER"):"{}")).toList();}
    private String forwarderJson(String responsibility) {
        return """
            {"forwarderResponsibility":"%s","forwarderName":"物流公司","forwarderAddress":"物流园仓库","forwarderContact":"收货联系人"}
            """.formatted(responsibility);
    }
    private String jsonDocuments() {
        return """
            {"contractNo":"SALE-1","contractReady":true,"paymentTableReady":true}
            """;
    }
    @Test void historicalCompletionWithoutContractEvidenceCanBeCorrected() {
        when(mapper.selectStepsForUpdate(1L)).thenReturn(List.of(row("DOCUMENTS_READY","{}")));
        service.complete(1L,"DOCUMENTS_READY",new PiWorkflowRequest("补录","SALE-NEW",true,true,false,null,null,null,null,null,null,null,null,null,null,null));
        verify(mapper).complete(eq(1L),eq(3),any(),eq("补录"),contains("SALE-NEW"));
    }
    private PiWorkflowStepRecord row(String code,String detail){var step=PiWorkflowStepCode.parse(code);return new PiWorkflowStepRecord(step.order(),code,step.stepName(),"COMPLETED",1L,null,null,detail);}
}
