package com.bike.server.pi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.bike.server.auth.UserAuthRecord;
import com.bike.server.common.*;
import com.bike.server.order.*;
import com.bike.server.stock.SkuInventoryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/** 新单配置门禁与已到账后付款修订的回归。 */
class PiFulfillmentRegressionTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final jakarta.validation.ValidatorFactory validation = Validation.buildDefaultValidatorFactory();
    @BeforeEach void login() {
        var auth = new UsernamePasswordAuthenticationToken("audit", null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        auth.setDetails(new UserAuthRecord(1L, "audit", "unused", "INTERNAL", null, null, true));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
    @AfterEach void close() { SecurityContextHolder.clearContext(); validation.close(); }

    @Test void newSelectionRequiresProfileAndHistoricalDocumentRemainsCompatible() throws Exception {
        var mapper = mock(PiFulfillmentProfileMapper.class);
        var profiles = new PiFulfillmentProfileServiceImpl(mapper, mock(PiTemplateMapper.class), mock(PiBusinessMapper.class),
                mock(OperationLogMapper.class), json, validation.getValidator());
        var document = json.readValue("""
            {"business":{"warehouseId":2,"domesticShipment":false,"collectionEntity":"HONG_KONG"}}
            """, PiDocumentSnapshot.class);
        assertThatThrownBy(() -> profiles.validateSelection(document, "EXPORT", true)).isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo("PI_PROFILE_REQUIRED"));
        assertThatCode(() -> profiles.validateSelection(document, "EXPORT", false)).doesNotThrowAnyException();
        assertThat(PiWorkflowServiceImpl.stepsFor(profiles.resolveProfile(document)))
                .doesNotContain(PiWorkflowStepCode.CASE_CLOSED, PiWorkflowStepCode.CI_SENT, PiWorkflowStepCode.CMR_COLLECTED);
        verifyNoInteractions(mapper);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"0","10","100"})
    void creditRevisionCanBeConfirmedBeforeReapproval(String received) throws Exception {
        var pis = mock(PiMapper.class);
        var businessMapper = mock(PiBusinessMapper.class);
        var series = mock(PiSeriesMapper.class);
        var workflow = mock(PiWorkflowMapper.class);
        var logs = mock(OperationLogMapper.class);
        var profiles = mock(PiFulfillmentProfileService.class);
        var business = new PiBusinessServiceImpl(businessMapper, pis, series, workflow,
                mock(OrderMapper.class), mock(OrderReservationMapper.class), mock(SkuInventoryMapper.class), logs, json);
        ReflectionTestUtils.setField(business, "profiles", profiles);
        var service = CustomerScopeTestSupport.isolated(new PiServiceImpl(pis, mock(PiDocumentService.class), logs,
                json, validation.getValidator(), mock(OrderReservationCoordinator.class)));
        ReflectionTestUtils.setField(service, "business", business);
        ReflectionTestUtils.setField(service, "fulfillmentProfiles", profiles);
        ReflectionTestUtils.setField(service, "series", series);
        var original = pi(1L, "CONFIRMED");
        var revision = pi(2L, "DRAFT");
        revision.setPreviousRevisionId(1L);
        revision.setRevision(2);
        String document = """
            {"sellerName":"Seller EU","paymentTerms":"Credit","deliveryTerms":"Pickup","templateCode":"EU","templateVersion":"1",
             "bankName":"Bank","bankAccount":"Account","beneficiary":"Seller EU",
             "business":{"warehouseId":2,"domesticShipment":false,"collectionEntity":"Seller EU","fulfillmentProfileId":9,"paymentDueDate":"2099-01-01"}}
            """;
        revision.setDocumentJson(document);
        when(pis.selectById(1L)).thenReturn(original);
        when(pis.selectById(2L)).thenReturn(revision);
        when(pis.selectForUpdate(1L)).thenReturn(original);
        when(pis.selectForUpdate(2L)).thenReturn(revision);
        when(pis.lockPi(anyLong())).thenAnswer(call -> call.getArgument(0));
        when(businessMapper.selectDocument(2L)).thenReturn(document);
        when(businessMapper.countEnabledWarehouse(2L)).thenReturn(1);
        when(profiles.resolveProfile(any())).thenReturn(PiFulfillmentProfileTest.profile());
        // 旧修订有授权，新修订只继承合同和货代，尚无后付款授权。
        when(workflow.selectSteps(1L)).thenReturn(List.of(new PiWorkflowStepRecord(13,"CREDIT_APPROVED","授权","COMPLETED",1L,LocalDateTime.now(),"已批准","{}")));
        when(series.lockSeries(10L)).thenReturn(new PiSeriesMapper.SeriesLock(10L,1L,3,1));
        when(series.publishRevision(10L,2L,1L,2)).thenReturn(1);
        when(pis.updateStatus(anyLong(),anyString(),anyString(),any(),anyBoolean())).thenReturn(1);
        when(pis.updatePaymentStatus(anyLong(),anyString())).thenReturn(1);
        when(logs.insert(any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(1);
        when(pis.paymentTotal(2L)).thenReturn(new BigDecimal(received));
        assertThatCode(() -> service.confirm(2L)).doesNotThrowAnyException();
        verify(pis).updateStatus(eq(2L), eq("DRAFT"), eq("CONFIRMED"), any(), eq(false));
        // 修订不继承后付款授权，既有占用仍单独校验，实际放行需再次授权。
        verify(businessMapper, never()).countWarehouseStock(anyLong(), anyLong());
        verify(workflow, never()).selectSteps(1L);
    }

    private PiView pi(long id, String status) {
        var pi = new PiView(); pi.setId(id); pi.setSeriesId(10L); pi.setCustomerId(1L); pi.setStatus(status);
        pi.setTradeType("EXPORT"); pi.setCurrencyCode("EUR"); pi.setRevision(1);
        pi.setTotalAmount(new BigDecimal("100")); pi.setPaidAmount(BigDecimal.TEN);
        pi.setDepositRequiredAmount(BigDecimal.ZERO); pi.setShipmentRequiredAmount(BigDecimal.ZERO);
        return pi;
    }
}
