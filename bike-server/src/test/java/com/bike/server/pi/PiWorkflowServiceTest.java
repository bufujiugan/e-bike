package com.bike.server.pi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bike.server.auth.UserAuthRecord;
import com.bike.server.common.BusinessException;
import com.bike.server.common.OperationLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class PiWorkflowServiceTest {
    private final PiMapper pis = mock(PiMapper.class);
    private final PiWorkflowMapper mapper = mock(PiWorkflowMapper.class);
    private final OperationLogMapper logs = mock(OperationLogMapper.class);
    private final PiWorkflowService service = com.bike.server.common.CustomerScopeTestSupport.isolated(
            new PiWorkflowServiceImpl(pis, mapper, logs, new ObjectMapper().findAndRegisterModules()));

    @BeforeEach
    void setup() {
        login("ROLE_SUPER_ADMIN", "PERM_PI_READ", "PERM_PI_WRITE");
        when(logs.insert(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
    }

    @AfterEach
    void cleanup() { SecurityContextHolder.clearContext(); }

    @Test
    void cannotConfirmPaymentWithoutAReceiptRecord() {
        PiView pi = pi("DRAFT", "UNPAID");
        when(pis.selectForUpdate(1L)).thenReturn(pi);
        when(pis.selectLogistics(1L)).thenReturn(List.of());
        when(mapper.selectStepsForUpdate(1L)).thenReturn(pendingSteps());
        when(mapper.complete(eq(1L), eq(1), any(), any(), isNull())).thenReturn(1);

        assertThatThrownBy(() -> service.complete(1L, "PAYMENT_CONFIRMED", new PiWorkflowRequest(null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("先登记实际到账");
        verify(mapper).complete(eq(1L), eq(1), any(), any(), isNull());
        verify(mapper, never()).complete(eq(1L), eq(2), any(), any(), any());
    }

    @Test
    void shippedLogisticsOnlyCompletesTrackingAndNeverCustomerReceipt() {
        PiView pi = pi("RELEASED", "PAID");
        PiLogisticsView shipment = new PiLogisticsView();
        shipment.setStatus("SHIPPED"); shipment.setCarrier("Forwarder"); shipment.setTrackingNo("TRACK-1");
        when(pis.selectById(1L)).thenReturn(pi);
        when(pis.selectLogistics(1L)).thenReturn(List.of(shipment));
        when(mapper.selectSteps(1L)).thenReturn(List.of());

        PiWorkflowView result = service.get(1L);

        assertThat(step(result, "TRACKING_RECORDED").status()).isEqualTo("COMPLETED");
        assertThat(step(result, "CUSTOMER_RECEIVED").status()).isEqualTo("PENDING");
        assertThat(step(result, "FORWARDER_SELECTED").status()).isEqualTo("PENDING");
        assertThat(step(result, "DOMESTIC_EXPRESS_BOOKED").status()).isEqualTo("PENDING");
        assertThat(step(result, "DOCUMENTS_READY").status()).isEqualTo("PENDING");
    }

    @Test
    void savesStructuredDocumentFactsWhenCompletingTheStep() {
        PiView pi = pi("CONFIRMED", "PAID");
        List<PiWorkflowStepRecord> before = stepsCompletedThrough(2);
        when(pis.selectForUpdate(1L)).thenReturn(pi);
        when(pis.selectLogistics(1L)).thenReturn(List.of());
        when(mapper.selectStepsForUpdate(1L)).thenReturn(before, before);
        when(mapper.selectSteps(1L)).thenReturn(stepsCompletedThrough(3));
        when(mapper.complete(eq(1L), eq(3), any(), eq("三张表已核对"), contains("CONTRACT-100"))).thenReturn(1);
        PiWorkflowRequest request = new PiWorkflowRequest("三张表已核对", "CONTRACT-100", true, true, true,
                null, null, null, null, null, null, null, null, null, null, null);

        PiWorkflowView result = service.complete(1L, "DOCUMENTS_READY", request);

        assertThat(result.currentStep()).isEqualTo("PRODUCTION_FOLLOW_UP");
        verify(mapper).complete(eq(1L), eq(3), any(), eq("三张表已核对"), contains("CONTRACT-100"));
    }

    @Test
    void customerReceiptRequiresExplicitReceiptDate() {
        PiView pi = pi("RELEASED", "PAID");
        PiLogisticsView shipment = new PiLogisticsView();
        shipment.setStatus("SHIPPED"); shipment.setCarrier("Forwarder"); shipment.setTrackingNo("TRACK-1");
        when(pis.selectForUpdate(1L)).thenReturn(pi);
        when(pis.selectLogistics(1L)).thenReturn(List.of(shipment));
        when(mapper.selectStepsForUpdate(1L)).thenReturn(stepsCompletedThrough(7));

        assertThatThrownBy(() -> service.complete(1L, "CUSTOMER_RECEIVED", new PiWorkflowRequest("客户确认")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("收货日期");
        verify(mapper, never()).complete(eq(1L), eq(8), any(), any(), any());
    }

    @Test
    void agentWorkflowDoesNotExposePaymentOrDocumentProgress() {
        loginAsAgent();
        PiView pi = pi("RELEASED", "PAID"); pi.setAgentId(20L);
        when(pis.selectById(1L)).thenReturn(pi);
        when(pis.selectLogistics(1L)).thenReturn(List.of());
        when(mapper.selectSteps(1L)).thenReturn(stepsCompletedThrough(4));

        PiWorkflowView result = service.get(1L);

        assertThat(result.steps()).extracting(PiWorkflowStepView::code)
                .containsExactly("PI_CREATED", "TRACKING_RECORDED", "CUSTOMER_RECEIVED");
        assertThat(result.steps()).noneMatch(item -> item.code().equals("PAYMENT_CONFIRMED"));
    }

    @Test
    void inheritsCompletedStepsFromPreviousRevisionBeforeProgressing() {
        PiView pi = pi("CONFIRMED", "PAID"); pi.setPreviousRevisionId(9L);
        when(pis.selectForUpdate(1L)).thenReturn(pi);
        when(pis.selectLogistics(1L)).thenReturn(List.of());
        when(mapper.selectStepsForUpdate(1L)).thenReturn(stepsCompletedThrough(3));
        when(mapper.selectSteps(1L)).thenReturn(stepsCompletedThrough(4));
        when(mapper.insertUpdate(eq(1L), eq("PRODUCTION_FOLLOW_UP"), contains("生产中"), eq("生产中"), any())).thenReturn(1);
        when(mapper.complete(eq(1L), eq(4), any(), eq("生产中"), contains("生产中"))).thenReturn(1);
        PiWorkflowRequest request = new PiWorkflowRequest("生产中", null, null, null, null, "生产中",
                LocalDate.now().plusDays(10), "材料齐备", null, null, null, null, null, null, null, true);

        service.recordProductionUpdate(1L, request);

        verify(mapper).inheritCompleted(1L, 9L);
        verify(mapper).complete(eq(1L), eq(4), any(), eq("生产中"), contains("材料齐备"));
    }

    @Test
    void productionUpdateOnlyCompletesTheStepWhenExplicitlyConfirmed() {
        PiView pi = pi("CONFIRMED", "PAID");
        when(pis.selectForUpdate(1L)).thenReturn(pi);
        when(pis.selectLogistics(1L)).thenReturn(List.of());
        when(mapper.selectStepsForUpdate(1L)).thenReturn(stepsCompletedThrough(3));
        when(mapper.selectSteps(1L)).thenReturn(stepsCompletedThrough(3));
        when(mapper.insertUpdate(eq(1L), eq("PRODUCTION_FOLLOW_UP"), contains("车架组装中"), eq("第一次跟进"), any()))
                .thenReturn(1);
        PiWorkflowRequest request = new PiWorkflowRequest("第一次跟进", null, null, null, null, "车架组装中",
                LocalDate.now().plusDays(7), "电池待到货", null, null, null, null, null, null, null, false);

        PiWorkflowView result = service.recordProductionUpdate(1L, request);

        assertThat(result.currentStep()).isEqualTo("PRODUCTION_FOLLOW_UP");
        verify(mapper).insertUpdate(eq(1L), eq("PRODUCTION_FOLLOW_UP"), contains("车架组装中"), eq("第一次跟进"), any());
        verify(mapper, never()).complete(eq(1L), eq(4), any(), any(), any());
    }

    private PiView pi(String status, String paymentStatus) {
        PiView pi = new PiView();
        pi.setId(1L); pi.setCustomerId(10L); pi.setStatus(status); pi.setPaymentStatus(paymentStatus);
        return pi;
    }

    private PiWorkflowStepView step(PiWorkflowView workflow, String code) {
        return workflow.steps().stream().filter(item -> item.code().equals(code)).findFirst().orElseThrow();
    }

    private List<PiWorkflowStepRecord> pendingSteps() { return stepsCompletedThrough(0); }

    private List<PiWorkflowStepRecord> stepsCompletedThrough(int completedOrder) {
        return java.util.Arrays.stream(PiWorkflowStepCode.values())
                .map(step -> new PiWorkflowStepRecord(step.order(), step.name(), step.stepName(),
                        step.order() <= completedOrder ? "COMPLETED" : "PENDING", null, null, null, null))
                .toList();
    }

    private static void login(String... authorities) {
        var auth = new UsernamePasswordAuthenticationToken(
                new UserAuthRecord(1L, "tester", "tester", "WEB_OPERATOR", null, null, true), "x",
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static void loginAsAgent() {
        var auth = new UsernamePasswordAuthenticationToken(
                "agent", "x",
                List.of(new SimpleGrantedAuthority("ROLE_WEB_AGENT"), new SimpleGrantedAuthority("PERM_PI_READ")));
        auth.setDetails(new UserAuthRecord(2L, "agent", "agent", "AGENT", 20L, null, true));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
