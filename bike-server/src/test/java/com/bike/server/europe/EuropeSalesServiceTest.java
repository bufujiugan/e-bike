package com.bike.server.europe;

import com.bike.server.auth.UserAuthRecord;
import com.bike.server.common.*;
import com.bike.server.order.*;
import com.bike.server.pi.*;
import com.bike.server.production.ProductionGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EuropeSalesServiceTest {
    private final EuropeSalesMapper mapper=mock(EuropeSalesMapper.class);
    private final OrderMapper orders=mock(OrderMapper.class);
    private final PiMapper pis=mock(PiMapper.class);
    private final PiSeriesMapper series=mock(PiSeriesMapper.class);
    private final PiBusinessMapper warehouses=mock(PiBusinessMapper.class);
    private final CustomerScopeAccess scope=mock(CustomerScopeAccess.class);
    private final ProductionGuard guard=mock(ProductionGuard.class);
    private final OperationLogMapper audit=mock(OperationLogMapper.class);
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private ValidatorFactory validation;
    private EuropeSalesService service;
    private EuropeSalesMapper.StoredOrder stored;

    @BeforeEach void setup() throws Exception {
        login("ROLE_SUPER_ADMIN");
        validation=Validation.buildDefaultValidatorFactory();
        service=new EuropeSalesServiceImpl(mapper,orders,pis,series,warehouses,scope,guard,audit,json,validation.getValidator());
        fixture("FACTORY_EXPORT",Map.of());
        when(mapper.selectOrder(1)).thenAnswer(call->stored);
        when(mapper.selectOrderForUpdate(1)).thenAnswer(call->stored);
        when(mapper.updateSteps(eq(1L),anyInt(),anyString())).thenAnswer(call->{
            if(stored.revision()!=call.<Integer>getArgument(1)) return 0;
            stored=new EuropeSalesMapper.StoredOrder(1,"FS1",10L,null,"客户",null,"EUR",stored.detailsJson(),call.getArgument(2),stored.revision()+1,stored.createdAt(),LocalDateTime.now());
            return 1;
        });
        when(audit.insert(any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(1);
        when(orders.countEnabledCustomer(10L)).thenReturn(1);
    }
    @AfterEach void cleanup() { validation.close();SecurityContextHolder.clearContext(); }
    private void login(String authority) {
        var user=new UserAuthRecord(1L,"europe-test","unused","INTERNAL",null,null,true);
        var auth=new UsernamePasswordAuthenticationToken(user.username(),null,List.of(new SimpleGrantedAuthority(authority)));
        auth.setDetails(user);SecurityContextHolder.getContext().setAuthentication(auth);
    }
    private FactorySalesRequest draft(String type) throws Exception {
        return json.readValue("{\"requestId\":\"request-1\",\"revision\":1,\"customerId\":10,\"businessType\":\""+type+"\",\"currencyCode\":\"EUR\"}",FactorySalesRequest.class);
    }
    private void fixture(String type,Map<String,FactorySalesView.StepConfirmation> steps) throws Exception {
        stored=new EuropeSalesMapper.StoredOrder(1,"FS1",10L,null,"客户",null,"EUR",json.writeValueAsString(draft(type)),json.writeValueAsString(steps),1,LocalDateTime.now(),LocalDateTime.now());
    }
    private FactorySalesView.StepConfirmation confirmation() { return new FactorySalesView.StepConfirmation("经办人",LocalDateTime.now(),null); }
    private SalesSettlementRequest payment(String requestId) {
        return new SalesSettlementRequest(requestId,new BigDecimal("10.25"),"EUR",LocalDateTime.of(2026,1,1,12,0),null,null,null);
    }
    private SalesSettlementView entry(String status) {
        var r=payment("payment-1");return new SalesSettlementView(9,r.requestId(),r.amount(),r.currencyCode(),r.occurredAt(),null,null,null,status,LocalDateTime.now(),null);
    }
    @ParameterizedTest @ValueSource(strings={"FACTORY_DOMESTIC","FACTORY_EXPORT"})
    void bothDirectTypesRequireCollectionBeforePickupButNoAttachments(String type) throws Exception {
        fixture(type,Map.of());
        assertThatThrownBy(()->service.confirmFactoryStep(1,"PICKUP",1,true,null)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("FACTORY_COLLECTION_CONFIRM_REQUIRED"));
        var collected=service.confirmFactoryStep(1,"COLLECTION",1,true,null);
        assertThat(collected.steps()).containsKey("COLLECTION");
        var picked=service.confirmFactoryStep(1,"PICKUP",collected.revision(),true,null);
        assertThat(picked.steps()).containsKeys("COLLECTION","PICKUP");
        verify(mapper,never()).insertSettlement(any(),anyLong(),any(),any());
        verifyNoInteractions(orders,warehouses,pis,series);
    }
    @Test void resetCollectionAlsoResetsPickupAndClosure() throws Exception {
        fixture("FACTORY_EXPORT",Map.of("CONTRACT",confirmation(),"COLLECTION",confirmation(),"PICKUP",confirmation(),"CLOSED",confirmation()));
        var result=service.confirmFactoryStep(1,"COLLECTION",1,false,"核对");
        assertThat(result.steps()).containsOnlyKeys("CONTRACT");
    }
    @Test void staleRevisionCannotOverwriteProgress() {
        assertThatThrownBy(()->service.confirmFactoryStep(1,"CONTRACT",2,true,null)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("EUROPE_REVISION_CONFLICT"));
        verify(mapper,never()).updateSteps(anyLong(),anyInt(),any());
    }
    @Test void incompleteDraftIsAcceptedAndCreateRetryIsIdempotent() throws Exception {
        var request=draft("FACTORY_EXPORT");
        when(mapper.selectByRequestId("request-1")).thenReturn(stored);
        assertThat(service.createFactoryOrder(request).id()).isEqualTo(1);
        assertThat(validation.getValidator().validate(request)).isEmpty();
        verify(mapper,never()).insertOrder(any(),any(),any(),any());
    }
    @Test void readPermissionDoesNotGrantProgressOrFinanceWrites() {
        login("PERM_PI_READ");
        assertThatCode(()->service.getFactoryOrder(1)).doesNotThrowAnyException();
        assertThatThrownBy(()->service.confirmFactoryStep(1,"COLLECTION",1,true,null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.recordSettlement("FACTORY",1,payment("p"))).isInstanceOf(BusinessException.class);
    }
    @Test void customerScopeDenialPreventsAccessAndWrites() {
        doThrow(new BusinessException("CUSTOMER_SCOPE_DENIED","禁止访问")).when(scope).requireCustomer(10L);
        assertThatThrownBy(()->service.getFactoryOrder(1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.confirmFactoryStep(1,"COLLECTION",1,true,null)).isInstanceOf(BusinessException.class);
        verify(mapper,never()).updateSteps(anyLong(),anyInt(),any());
    }
    @Test void existingPiCannotBeReusedAsFactoryFulfillment() throws Exception {
        var request=json.readValue(stored.detailsJson().replace("\"orderIds\":null","\"orderIds\":[7]"),FactorySalesRequest.class);
        var order=new SalesOrderView();order.setId(7L);order.setCustomerId(10L);
        when(orders.selectForUpdate(7)).thenReturn(order);when(series.currentPiForOrder(7)).thenReturn(20L);
        assertThatThrownBy(()->service.updateFactoryOrder(1,request)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("FACTORY_ORDER_HAS_PI"));
        verify(mapper,never()).insertOrderLink(anyLong(),anyLong());
    }
    @Test void shippedOrderCannotBeDetached() throws Exception {
        when(mapper.selectLinkedOrders(1)).thenReturn(List.of(new FactorySalesView.LinkedOrder(7,"SO7",2,1,"PARTIAL_SHIPPED")));
        assertThatThrownBy(()->service.updateFactoryOrder(1,draft("FACTORY_EXPORT"))).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("FACTORY_SHIPPED_IDENTITY"));
        verify(mapper,never()).deleteOrderLink(anyLong(),anyLong());
    }
    @Test void changingFactoryRestoresCollectionConfirmationButKeepsEditableNotes() throws Exception {
        fixture("FACTORY_EXPORT",Map.of("COLLECTION",confirmation(),"PICKUP",confirmation()));
        var node=json.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(draft("FACTORY_EXPORT"));
        node.put("factoryName","新工厂");node.put("remark","补录合同");
        var request=json.treeToValue(node,FactorySalesRequest.class);
        when(mapper.updateOrder(eq(1L),eq(request),anyString())).thenAnswer(call->{
            stored=new EuropeSalesMapper.StoredOrder(1,"FS1",10L,null,"客户",null,"EUR",call.getArgument(2),stored.stepsJson(),2,stored.createdAt(),LocalDateTime.now());
            return 1;
        });
        var result=service.updateFactoryOrder(1,request);
        assertThat(result.steps()).doesNotContainKeys("COLLECTION","PICKUP");
        assertThat(result.details().remark()).isEqualTo("补录合同");
    }
    @Test void ledgerRetryDoesNotDuplicateOrMarkCollection() {
        when(mapper.selectSettlementRequest("FACTORY",1,"payment-1")).thenReturn(entry("CONFIRMED"));
        service.recordSettlement("FACTORY",1,payment("payment-1"));
        verify(mapper,never()).insertSettlement(any(),anyLong(),any(),any());
        verify(mapper,never()).updateSteps(anyLong(),anyInt(),any());
    }
    @Test void reversalRestoresCollectionAndPickupToPending() throws Exception {
        fixture("FACTORY_EXPORT",Map.of("COLLECTION",confirmation(),"PICKUP",confirmation(),"CLOSED",confirmation()));
        when(mapper.selectSettlements("FACTORY",1)).thenReturn(List.of(entry("CONFIRMED")));
        when(mapper.reverseSettlement(eq("FACTORY"),eq(1L),eq(9L),any(),any())).thenReturn(1);
        service.reverseSettlement("FACTORY",1,9,"金额错误");
        assertThat(service.getFactoryOrder(1).steps()).isEmpty();
        verify(guard).lock();
    }
    @Test void repeatedReversalIsIdempotent() {
        when(mapper.selectSettlements("FACTORY",1)).thenReturn(List.of(entry("REVERSED")));
        service.reverseSettlement("FACTORY",1,9,"重复核对");
        verify(mapper,never()).reverseSettlement(any(),anyLong(),anyLong(),any(),any());
        verify(mapper,never()).updateSteps(anyLong(),anyInt(),any());
    }
    @Test void supplementOnlyChangesReferenceFieldsAndWritesAudit() {
        when(mapper.selectSettlements("FACTORY",1)).thenReturn(List.of(entry("CONFIRMED")));
        var supplement=new SalesSettlementSupplement("Totem","REF-1","后补");
        when(mapper.updateSettlementSupplement("FACTORY",1,9,supplement)).thenReturn(1);
        service.supplementSettlement("FACTORY",1,9,supplement);
        verify(mapper).updateSettlementSupplement("FACTORY",1,9,supplement);
        verify(mapper,never()).insertSettlement(any(),anyLong(),any(),any());
    }
    @Test void piPaymentsUseSeriesAcrossRevisionsAndRejectDraft() {
        var pi=new PiView();pi.setId(12L);pi.setCustomerId(10L);pi.setSeriesId(99L);pi.setCurrencyCode("EUR");pi.setStatus("DRAFT");
        when(pis.selectById(12)).thenReturn(pi);when(pis.selectForUpdate(12)).thenReturn(pi);
        assertThatThrownBy(()->service.recordSettlement("PI",12,payment("p"))).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("SETTLEMENT_PI_CONFIRM_REQUIRED"));
        pi.setStatus("CONFIRMED");when(mapper.insertSettlement(eq("PI"),eq(99L),any(),any())).thenReturn(1);
        service.recordSettlement("PI",12,payment("p"));
        verify(series,times(2)).lockSeries(99);
        verify(mapper).insertSettlement(eq("PI"),eq(99L),any(),any());
    }
    @Test void moneySerializationPreservesAllDecimalDigits() throws Exception {
        var r=entry("CONFIRMED");var large=new SalesSettlementView(r.id(),r.requestId(),new BigDecimal("9999999999999999.99"),r.currencyCode(),r.occurredAt(),null,null,null,r.status(),r.createdAt(),null);
        assertThat(json.readTree(json.writeValueAsString(large)).get("amount").asText()).isEqualTo("9999999999999999.99");
        assertThat(json.readTree(json.writeValueAsString(large)).get("amount").isTextual()).isTrue();
    }
}
