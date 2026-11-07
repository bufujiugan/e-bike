package com.bike.server.pi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.bike.server.auth.UserAuthRecord;
import com.bike.server.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class PiFulfillmentProfileTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final PiFulfillmentProfileMapper mapper=mock(PiFulfillmentProfileMapper.class);
    private final PiTemplateMapper templates=mock(PiTemplateMapper.class);
    private final PiBusinessMapper businessMapper=mock(PiBusinessMapper.class);
    private final OperationLogMapper logs=mock(OperationLogMapper.class);
    private final jakarta.validation.ValidatorFactory validation=Validation.buildDefaultValidatorFactory();
    private final PiFulfillmentProfileServiceImpl service=new PiFulfillmentProfileServiceImpl(mapper,templates,businessMapper,logs,json,validation.getValidator());
    @BeforeEach void login(){
        var authentication=new UsernamePasswordAuthenticationToken("test",null,List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        authentication.setDetails(new UserAuthRecord(1L,"test","unused","INTERNAL",null,null,true));SecurityContextHolder.getContext().setAuthentication(authentication);
    }
    @AfterEach void close(){SecurityContextHolder.clearContext();validation.close();}
    static PiFulfillmentRules creditRules(){return new PiFulfillmentRules("CREDIT","CUSTOMER_PICKUP",false,false,false,true,true,true,List.of("CI","RD","CMR"),List.of(2L));}
    static PiFulfillmentProfile profile(){return new PiFulfillmentProfile(9L,"STANDARD","1","后付款提货","EXPORT","Seller EU","EU","1",creditRules(),true,LocalDateTime.now());}
    private PiDocumentSnapshot document() throws Exception {return json.readValue("""
        {"templateCode":"EU","templateVersion":"1","business":{"warehouseId":2,"domesticShipment":false,"collectionEntity":"Seller EU","fulfillmentProfileId":9,"paymentDueDate":"2099-01-01"}}
        """,PiDocumentSnapshot.class);}
    private void stored(boolean enabled) throws Exception {
        var p=profile();var request=new PiFulfillmentProfileRequest(p.profileCode(),p.version(),p.name(),p.tradeType(),p.collectionEntity(),p.templateCode(),p.templateVersion(),p.rules(),"测试版本");
        when(mapper.selectProfile(9L)).thenReturn(new PiFulfillmentProfileMapper.StoredProfile(9L,json.writeValueAsString(request),enabled,LocalDateTime.now()));
    }
    @Test void disabledVersionRemainsReadableButCannotBeSelectedForNewBusiness() throws Exception {
        stored(false);var document=document();service.validateSelection(document,"EXPORT",false);
        assertThatThrownBy(()->service.validateSelection(document,"EXPORT",true)).isInstanceOf(BusinessException.class).hasMessageContaining("停用");
    }
    @Test void tamperedCollectionEntityIsRejected() throws Exception {
        stored(true);var node=json.valueToTree(document());((com.fasterxml.jackson.databind.node.ObjectNode)node.get("business")).put("collectionEntity","Other");
        var changed=json.treeToValue(node,PiDocumentSnapshot.class);
        assertThatThrownBy(()->service.validateSelection(changed,"EXPORT",true)).hasMessageContaining("收款主体");
    }
    @Test void warehouseAndDueDateAreEnforced() throws Exception {
        stored(true);var node=json.valueToTree(document());((com.fasterxml.jackson.databind.node.ObjectNode)node.get("business")).put("warehouseId",3);
        var changed=json.treeToValue(node,PiDocumentSnapshot.class);assertThatThrownBy(()->service.validateSelection(changed,"EXPORT",true)).hasMessageContaining("仓库");
        ((com.fasterxml.jackson.databind.node.ObjectNode)node.get("business")).put("warehouseId",2).remove("paymentDueDate");
        var noDate=json.treeToValue(node,PiDocumentSnapshot.class);assertThatThrownBy(()->service.validateSelection(noDate,"EXPORT",true)).hasMessageContaining("到期日");
    }
    @Test void sameRulesApplyRegardlessOfCountryAndPickupSkipsForwarderWarehouse() {
        var steps=PiWorkflowServiceImpl.stepsFor(profile());
        assertThat(steps).contains(PiWorkflowStepCode.CREDIT_APPROVED,PiWorkflowStepCode.CI_SENT,PiWorkflowStepCode.RD_SIGNED,PiWorkflowStepCode.PAYMENT_SETTLED,PiWorkflowStepCode.CASE_CLOSED)
            .doesNotContain(PiWorkflowStepCode.PAYMENT_CONFIRMED,PiWorkflowStepCode.DOMESTIC_EXPRESS_BOOKED,PiWorkflowStepCode.WAREHOUSE_ARRIVED,PiWorkflowStepCode.PURCHASE_CONTRACT_READY);
        assertThat(steps.indexOf(PiWorkflowStepCode.GOODS_PREPARED)).isLessThan(steps.indexOf(PiWorkflowStepCode.STOCK_DISPATCHED));
    }
    @Test void oldTermsStillHaveNoConfigurationAndDoNotChangeSerializedRequestIdentity() throws Exception {
        String value=json.writeValueAsString(new PiBusinessTerms(2L,false,"HONG_KONG"));
        assertThat(value).doesNotContain("fulfillmentProfileId","paymentDueDate","orderSource");
        assertThat(PiWorkflowServiceImpl.stepsFor(null)).hasSize(11).doesNotContain(PiWorkflowStepCode.CREDIT_APPROVED,PiWorkflowStepCode.CASE_CLOSED);
    }
    @Test void receiptAloneCannotCloseConfiguredBusiness() throws Exception {
        var pis=mock(PiMapper.class);var workflow=mock(PiWorkflowMapper.class);var business=mock(PiBusinessService.class);var profiles=mock(PiFulfillmentProfileService.class);
        var handler=CustomerScopeTestSupport.isolated(new PiWorkflowServiceImpl(pis,workflow,logs,json));
        ReflectionTestUtils.setField(handler,"business",business);ReflectionTestUtils.setField(handler,"profiles",profiles);ReflectionTestUtils.setField(handler,"profileMapper",mapper);
        var pi=new PiView();pi.setId(1L);pi.setCustomerId(1L);pi.setStatus("CONFIRMED");pi.setTotalAmount(java.math.BigDecimal.TEN);pi.setPaidAmount(java.math.BigDecimal.ZERO);
        when(pis.selectForUpdate(1L)).thenReturn(pi);when(business.document(1L)).thenReturn(document());when(profiles.resolveProfile(any())).thenReturn(profile());
        when(business.allTracked(1L)).thenReturn(true);
        assertThatThrownBy(()->handler.complete(1L,"CASE_CLOSED",new PiWorkflowRequest("核对结案"))).hasMessageContaining("不能结案");
        verify(workflow,never()).complete(anyLong(),anyInt(),any(),any(),any());
    }
    @Test void invalidAndInapplicableStepsCannotBeManuallyCompleted() throws Exception {
        var pis=mock(PiMapper.class);var workflow=mock(PiWorkflowMapper.class);var business=mock(PiBusinessService.class);var profiles=mock(PiFulfillmentProfileService.class);
        var handler=CustomerScopeTestSupport.isolated(new PiWorkflowServiceImpl(pis,workflow,logs,json));
        ReflectionTestUtils.setField(handler,"business",business);ReflectionTestUtils.setField(handler,"profiles",profiles);
        var pi=new PiView();pi.setId(1L);pi.setCustomerId(1L);pi.setStatus("CONFIRMED");when(pis.selectForUpdate(1L)).thenReturn(pi);
        when(business.document(1L)).thenReturn(document());when(profiles.resolveProfile(any())).thenReturn(profile());
        assertThatThrownBy(()->handler.complete(1L,"WAREHOUSE_ARRIVED",new PiWorkflowRequest("跳过"))).hasMessageContaining("不适用");
    }
    @Test void ordinaryWriterCannotApproveCreditButReleaseOfficerCan() throws Exception {
        var pis=mock(PiMapper.class);var workflow=mock(PiWorkflowMapper.class);var business=mock(PiBusinessService.class);var profiles=mock(PiFulfillmentProfileService.class);
        var handler=CustomerScopeTestSupport.isolated(new PiWorkflowServiceImpl(pis,workflow,logs,json));
        ReflectionTestUtils.setField(handler,"business",business);ReflectionTestUtils.setField(handler,"profiles",profiles);
        var pi=new PiView();pi.setId(1L);pi.setCustomerId(1L);pi.setStatus("CONFIRMED");when(pis.selectForUpdate(1L)).thenReturn(pi);
        when(business.document(1L)).thenReturn(document());when(profiles.resolveProfile(any())).thenReturn(profile());
        when(workflow.selectStepsForUpdate(1L)).thenReturn(List.of(new PiWorkflowStepRecord(3,"DOCUMENTS_READY","单据","COMPLETED",1L,LocalDateTime.now(),null,"{\"paymentTableReady\":true}")));
        when(workflow.complete(anyLong(),anyInt(),any(),any(),any())).thenReturn(1);when(logs.insert(any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(1);
        var writer=new UsernamePasswordAuthenticationToken("writer",null,List.of(new SimpleGrantedAuthority("PERM_PI_WRITE")));
        writer.setDetails(new UserAuthRecord(2L,"writer","unused","INTERNAL",null,null,true));SecurityContextHolder.getContext().setAuthentication(writer);
        assertThatThrownBy(()->handler.complete(1L,"CREDIT_APPROVED",new PiWorkflowRequest("授权"))).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo("PERMISSION_DENIED"));
        var officer=new UsernamePasswordAuthenticationToken("officer",null,List.of(new SimpleGrantedAuthority("PERM_PI_RELEASE")));
        officer.setDetails(new UserAuthRecord(3L,"officer","unused","INTERNAL",null,null,true));SecurityContextHolder.getContext().setAuthentication(officer);
        handler.complete(1L,"CREDIT_APPROVED",new PiWorkflowRequest("已核对合同与期限"));
        verify(workflow).complete(eq(1L),eq(13),eq(3L),eq("已核对合同与期限"),anyString());
    }
    @Test void documentsFromAnotherPiCannotCompleteRequiredDocumentStep() throws Exception {
        var pis=mock(PiMapper.class);var workflow=mock(PiWorkflowMapper.class);var business=mock(PiBusinessService.class);var profiles=mock(PiFulfillmentProfileService.class);
        var handler=CustomerScopeTestSupport.isolated(new PiWorkflowServiceImpl(pis,workflow,logs,json));
        ReflectionTestUtils.setField(handler,"business",business);ReflectionTestUtils.setField(handler,"profiles",profiles);ReflectionTestUtils.setField(handler,"profileMapper",mapper);
        var pi=new PiView();pi.setId(1L);pi.setCustomerId(1L);pi.setStatus("RELEASED");when(pis.selectForUpdate(1L)).thenReturn(pi);
        when(business.document(1L)).thenReturn(document());when(profiles.resolveProfile(any())).thenReturn(profile());when(business.allTracked(1L)).thenReturn(true);
        when(workflow.selectStepsForUpdate(1L)).thenReturn(List.of(new PiWorkflowStepRecord(8,"CUSTOMER_RECEIVED","送达","COMPLETED",1L,LocalDateTime.now(),null,"{\"receivedDate\":\"2026-01-01\"}")));
        var request=json.readValue("{\"note\":\"已发送\",\"arrivalProof\":\"other-pi-file\",\"receivedDate\":\"2026-01-01\"}",PiWorkflowRequest.class);
        assertThatThrownBy(()->handler.complete(1L,"CI_SENT",request)).hasMessageContaining("本PI");
        verify(workflow,never()).complete(anyLong(),anyInt(),any(),any(),any());
    }
}
