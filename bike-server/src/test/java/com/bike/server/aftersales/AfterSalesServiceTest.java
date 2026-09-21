package com.bike.server.aftersales;

import com.bike.server.auth.UserAuthRecord;
import com.bike.server.common.BusinessException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AfterSalesServiceTest {
    private AfterSalesMapper mapper;
    private AfterSalesServiceImpl service;

    @BeforeEach void setup() {
        mapper = mock(AfterSalesMapper.class);
        service = new AfterSalesServiceImpl(mapper);
        login("WEB_ADMIN", null, "ROLE_SUPER_ADMIN");
        when(mapper.insertAudit(anyLong(), anyString(), any(), anyString(), any(), any(), any())).thenReturn(1);
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void customerNeverSeesInternalNoteOrInternalConversationEvenWithAccidentalInternalRole() {
        login("AGENT", 8L, "ROLE_WEB_AGENT", "ROLE_SUPER_ADMIN", "ROLE_AFTER_SALES_TECH", "PERM_AFTER_SALES_READ");
        var row = caseRow("UNDER_REVIEW");
        row.setInternalNote("private liability discussion");
        when(mapper.selectById(1)).thenReturn(row);
        when(mapper.selectMessages(1, false)).thenReturn(List.of());
        assertThat(service.get(1).getInternalNote()).isNull();
        verify(mapper).selectMessages(1, false);
        verify(mapper, never()).selectMessages(1, true);
        row.setInternalNote("list must also be redacted");
        when(mapper.selectPage(null, null, 8L, 0, 20)).thenReturn(List.of(row));
        assertThat(service.list(null, null, 1, 20).records().getFirst().getInternalNote()).isNull();
    }

    @Test void customInternalPermissionCanReadInternalConversationWithoutFixedRoleName() {
        login("WEB_OPERATOR", null, "ROLE_CUSTOM_SERVICE", "PERM_AFTER_SALES_READ", "PERM_AFTER_SALES_TECH", "PERM_AFTER_SALES_SCOPE_ALL");
        var row = caseRow("UNDER_REVIEW");
        row.setInternalNote("private diagnosis");
        when(mapper.selectById(1)).thenReturn(row);
        assertThat(service.get(1).getInternalNote()).isEqualTo("private diagnosis");
        verify(mapper).selectMessages(1, true);
    }

    @Test void readOnlyAndFixedRoleWithoutWritePermissionCannotSendMessages() {
        login("WEB_OPERATOR", null, "ROLE_AFTER_SALES_STAFF", "PERM_AFTER_SALES_READ");
        assertCode("PERMISSION_DENIED", () -> service.addMessage(1, new AfterSalesMessageRequest("unauthorized", "CUSTOMER")));
        verifyNoInteractions(mapper);
    }

    @Test void agentFormOptionsNeverListOtherCustomersOrAgents() {
        login("AGENT", 8L, "ROLE_WEB_AGENT", "PERM_AFTER_SALES_WRITE");
        when(mapper.selectModels(8L)).thenReturn(List.of(new AfterSalesReferenceOption(4L, "authorized model")));
        var options = service.options();
        assertThat(options.get("customers")).isEmpty(); assertThat(options.get("agents")).isEmpty();
        assertThat(options.get("models")).extracting(AfterSalesReferenceOption::name).containsExactly("authorized model");
        verify(mapper, never()).selectCustomers(); verify(mapper, never()).selectAgents();
    }

    @Test void pdaCannotReadOrWriteEvenWithAllPermissionsAndAdminRole() {
        login("PDA_OPERATOR", null, "ROLE_PDA_OPERATOR", "ROLE_SUPER_ADMIN");
        assertCode("PERMISSION_DENIED", () -> service.list(null, null, 1, 20));
        assertCode("PERMISSION_DENIED", () -> service.get(1));
        assertCode("PERMISSION_DENIED", () -> service.create(request(null, null, null)));
        assertCode("PERMISSION_DENIED", () -> service.review(1, new AfterSalesReviewRequest("APPROVE", null)));
        verifyNoInteractions(mapper);
    }

    @Test void agentCannotPerformInternalActionsEvenWhenAdminOrTechnicalPermissionAccidentallyGranted() {
        login("AGENT", 8L, "ROLE_WEB_AGENT", "ROLE_SUPER_ADMIN");
        assertCode("FORBIDDEN", () -> service.review(1, new AfterSalesReviewRequest("APPROVE", null)));
        assertCode("FORBIDDEN", () -> service.saveSolution(1, new AfterSalesSolutionRequest(null, "repair", null)));
        assertCode("FORBIDDEN", () -> service.startProcessing(1));
        assertCode("FORBIDDEN", () -> service.addShipment(1, new AfterSalesShipmentRequest("motor", 1, "DHL", "123", false)));
        assertCode("FORBIDDEN", () -> service.resolve(1));
        verifyNoInteractions(mapper);
    }

    @Test void agentCannotAttachUnverifiedCustomerOrForeignVehicleOrWarrantyDecision() {
        login("AGENT", 8L, "ROLE_WEB_AGENT", "PERM_AFTER_SALES_READ", "PERM_AFTER_SALES_WRITE");
        assertCode("FORBIDDEN", () -> service.create(request(44L, 8L, null)));
        when(mapper.selectVehicleReference(55)).thenReturn(new AfterSalesVehicleReference(55L, "F55", 4L, 44L, 99L, "M", "B", "D"));
        assertCode("FORBIDDEN", () -> service.create(request(44L, 8L, 55L)));
        verify(mapper, never()).insertCase(anyString(), any(), any(), any());
    }

    @Test void agentCaseScopeIsImmutableDuringEdit() {
        when(mapper.selectForUpdate(1)).thenReturn(caseRow("DRAFT"));
        assertCode("AFTER_SALES_SCOPE_IMMUTABLE", () -> service.update(1, request(null, 99L, null)));
        verify(mapper, never()).updateCase(anyLong(), any(), any());
    }

    @Test void agentCannotBypassModelOptionsByPostingAnUnrelatedModelId() {
        login("AGENT", 8L, "ROLE_WEB_AGENT", "PERM_AFTER_SALES_WRITE");
        when(mapper.countEnabledAgent(8)).thenReturn(1);
        var body = new AfterSalesCaseCreateRequest(null, null, null, "F-TEST", 77L, null, null, null,
            null, null, "MOTOR", null, null, "failure", null, "PENDING");
        assertCode("MODEL_NOT_AUTHORIZED", () -> service.create(body));
        verify(mapper, never()).insertCase(anyString(), any(), any(), any());
    }

    @Test void reviewCannotApproveDraftOrReopenTerminalCasesOrSkipTechnicalPlan() {
        for (String status : List.of("DRAFT", "APPROVED", "IN_PROGRESS", "SHIPPED", "RESOLVED", "CLOSED", "REJECTED")) {
            when(mapper.selectForUpdate(1)).thenReturn(caseRow(status));
            assertCode("INVALID_STATUS_TRANSITION", () -> service.review(1, new AfterSalesReviewRequest("APPROVE", null)));
        }
        when(mapper.selectForUpdate(1)).thenReturn(caseRow("UNDER_REVIEW"));
        assertCode("AFTER_SALES_SOLUTION_REQUIRED", () -> service.review(1, new AfterSalesReviewRequest("APPROVE", null)));
        verify(mapper, never()).updateStatus(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test void savingTechnicalPlanPreservesReviewStateAndAuditsBeforeAndAfter() {
        var before = caseRow("UNDER_REVIEW");
        var after = caseRow("UNDER_REVIEW"); after.setSolution("replace controller");
        when(mapper.selectForUpdate(1)).thenReturn(before);
        when(mapper.saveSolution(eq(1L), any(), eq(7L))).thenReturn(1);
        when(mapper.selectById(1)).thenReturn(after);
        assertThat(service.saveSolution(1, new AfterSalesSolutionRequest("tested", "replace controller", "private")).getStatus()).isEqualTo("UNDER_REVIEW");
        verify(mapper, never()).updateStatus(anyLong(), anyString(), anyString(), any(), any());
        verify(mapper).insertAudit(eq(1L), eq("SOLUTION_SAVED"), eq(7L), eq("test-user"), contains("UNDER_REVIEW"), contains("replace controller"), any());
    }

    @Test void noTechnicalPlanCanBeEditedAfterApprovalWithoutReturningToReview() {
        for (String status : List.of("DRAFT", "SUBMITTED", "APPROVED", "IN_PROGRESS", "SHIPPED", "RESOLVED", "CLOSED", "REJECTED")) {
            when(mapper.selectForUpdate(1)).thenReturn(caseRow(status));
            assertCode("INVALID_STATUS_TRANSITION", () -> service.saveSolution(1, new AfterSalesSolutionRequest(null, "unapproved change", null)));
        }
        verify(mapper, never()).saveSolution(anyLong(), any(), any());
    }

    @Test void rejectedOrInfoRequestRequiresReasonAndUnknownActionsFail() {
        when(mapper.selectForUpdate(1)).thenReturn(caseRow("UNDER_REVIEW"));
        for (String action : List.of("REJECT", "REQUEST_INFO", "anything")) {
            assertCode("INVALID_PARAMETER", () -> service.review(1, new AfterSalesReviewRequest(action, null)));
        }
        verify(mapper, never()).updateStatus(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test void messageReturnsItsOwnInsertedRecordAndRejectsUnknownVisibility() {
        when(mapper.selectForUpdate(1)).thenReturn(caseRow("UNDER_REVIEW"));
        assertCode("INVALID_PARAMETER", () -> service.addMessage(1, new AfterSalesMessageRequest("text", "SECRET")));
        when(mapper.insertMessage(1, 7L, "CUSTOMER", "this request")).thenReturn(1);
        when(mapper.touchCase(1, 7L)).thenReturn(1);
        when(mapper.selectInsertedId()).thenReturn(91L);
        var own = new AfterSalesMessageView(91L, 1L, 7L, "CUSTOMER", "this request", LocalDateTime.now());
        when(mapper.selectMessageById(91)).thenReturn(own);
        assertThat(service.addMessage(1, new AfterSalesMessageRequest("this request", "CUSTOMER"))).isSameAs(own);
        verify(mapper, never()).selectMessages(anyLong(), anyBoolean());
        var ordered = inOrder(mapper);
        ordered.verify(mapper).insertMessage(1, 7L, "CUSTOMER", "this request");
        ordered.verify(mapper).selectInsertedId();
        ordered.verify(mapper).selectMessageById(91);
    }

    @Test void pendingShipmentBlocksResolutionAndCarrierIsRequiredForActualDispatch() {
        when(mapper.selectForUpdate(1)).thenReturn(caseRow("IN_PROGRESS"));
        when(mapper.countPendingShipments(1)).thenReturn(1L);
        assertCode("AFTER_SALES_SHIPMENT_PENDING", () -> service.resolve(1));
        assertCode("INVALID_PARAMETER", () -> service.addShipment(1, new AfterSalesShipmentRequest("motor", 1, " ", "tracking", false)));
        verify(mapper, never()).insertShipment(anyLong(), any(), any());
        verify(mapper, never()).updateStatus(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test void failedAuditFailsTheBusinessOperationInsteadOfSilentlyLosingHistory() {
        when(mapper.selectForUpdate(1)).thenReturn(caseRow("DRAFT"));
        when(mapper.selectById(1)).thenReturn(caseRow("SUBMITTED"));
        when(mapper.updateStatus(1, "DRAFT", "SUBMITTED", null, 7L)).thenReturn(1);
        when(mapper.insertAudit(anyLong(), anyString(), any(), anyString(), any(), any(), any())).thenReturn(0);
        assertCode("AFTER_SALES_AUDIT_FAILED", () -> service.submit(1));
    }

    static AfterSalesCaseView caseRow(String status) {
        var row = new AfterSalesCaseView(); row.setId(1L); row.setCaseNo("AS-TEST"); row.setAgentId(8L);
        row.setStatus(status); row.setDescription("failure"); row.setFrameSn("F-TEST"); row.setFaultType("MOTOR");
        return row;
    }
    static AfterSalesCaseCreateRequest request(Long customerId, Long agentId, Long vehicleId) {
        return new AfterSalesCaseCreateRequest(customerId, agentId, vehicleId, "F-TEST", null, null, null, null,
            null, null, "MOTOR", null, null, "failure", null, "PENDING");
    }
    static void login(String type, Long agentId, String... authorities) {
        var auth = UsernamePasswordAuthenticationToken.authenticated("test-user", null,
            java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        auth.setDetails(new UserAuthRecord(7, "test-user", "unused", type, agentId, null, true));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
    static void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(code));
    }
}
