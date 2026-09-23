package com.bike.server.pi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bike.server.auth.UserAuthRecord;
import com.bike.server.common.BusinessException;
import com.bike.server.common.OperationLogMapper;
import com.bike.server.order.OrderReservationCoordinator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class PiServiceTest {
    @Test void paymentReversalRequiresDedicatedInternalPermissionEvenWithAccidentalAdminRole() {
        var request=new PiPaymentReverseRequest("reverse",1L,"核对错误登记");
        login("ROLE_WEB_OPERATOR","PERM_PI_READ","PERM_PI_PAYMENT_CONFIRM");
        assertCode(()->service.reversePayment(1,1,request),"PERMISSION_DENIED");
        login("ROLE_WEB_AGENT","ROLE_SUPER_ADMIN");assertCode(()->service.reversePayment(1,1,request),"FORBIDDEN");
        login("ROLE_PDA_OPERATOR","ROLE_SUPER_ADMIN");assertCode(()->service.reversePayment(1,1,request),"PERMISSION_DENIED");
        verifyNoInteractions(mapper,reservations);
    }
    private final PiMapper mapper = mock(PiMapper.class);
    private final OperationLogMapper logs = mock(OperationLogMapper.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final OrderReservationCoordinator reservations = mock(OrderReservationCoordinator.class);
    private final PiServiceImpl service = new PiServiceImpl(mapper, mock(PiDocumentService.class), logs, json, validator, reservations);

    @BeforeEach
    void setup() {
        login("ROLE_SUPER_ADMIN");
        when(logs.insert(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
    }

    @AfterEach
    void logout() { SecurityContextHolder.clearContext(); }

    @Test
    void ordinaryReaderCannotReadPaymentsOrTotalReceived() {
        login("ROLE_WEB_OPERATOR", "PERM_PI_READ");
        PiView pi = pi("CONFIRMED");
        pi.setPaidAmount(money("80.00"));
        when(mapper.selectById(1L)).thenReturn(pi);
        PiView result = service.get(1);
        assertThat(result.getPaidAmount()).isNull();
        assertThat(result.getPaymentStatus()).isNull();
        assertThat(result.getPayments()).isEmpty();
        assertThat(result.getTotalAmount()).isEqualByComparingTo("100.00");
        verify(mapper, never()).selectPayments(anyLong());
    }

    @Test
    void editsAndCancellationRejectPaidOrPublishedDocuments() {
        var request = request(List.of(item(10L, 1, "100.00", null, null)), null, null, null, "30", "100");
        prepareLocked("CONFIRMED");
        assertCode(() -> service.update(1, request), "INVALID_STATUS_TRANSITION");
        prepareLocked("DRAFT");
        when(mapper.countPayments(1L)).thenReturn(1);
        assertCode(() -> service.update(1, request), "PI_HAS_PAYMENTS");
        assertCode(() -> service.cancel(1, new PiCancelRequest("客户取消")), "PI_HAS_PAYMENTS");
        verify(mapper, never()).updateStatus(anyLong(), any(), any(), any(), anyBoolean());
    }

    @Test
    void draftCannotRebindSourceOrderAndLocksOrderBeforePi() {
        PiView pi = prepareLocked("DRAFT");
        pi.setSourceOrderId(7L);
        when(mapper.lockOrder(7L)).thenReturn(7L);
        var request = request(List.of(item(10L, 1, "100.00", null, null)), null, null, null, "30", "100");
        assertCode(() -> service.update(1, request), "PI_SOURCE_ORDER_IMMUTABLE");
        var order = inOrder(mapper);
        order.verify(mapper).selectById(1L);
        order.verify(mapper).lockOrder(7L);
        order.verify(mapper).lockPi(1L);
        order.verify(mapper).selectForUpdate(1L);
    }

    @Test
    void serializesMoneyAsDecimalStringsSoBrowserCannotLoseCents() throws Exception {
        PiView pi = pi("DRAFT");
        pi.setTotalAmount(new BigDecimal("9999999999999999.99"));
        PiItemView item = new PiItemView();
        item.setUnitPrice(new BigDecimal("90071992547409.93"));
        pi.setItems(List.of(item));
        pi.setPayments(List.of(new PiPaymentView(1L, 1L, "pay", new BigDecimal("90071992547409.93"),
                "CNY", null, "CONFIRMED", LocalDateTime.now(), 1L, null)));
        var tree = json.readTree(json.writeValueAsString(pi));
        assertThat(tree.get("totalAmount").isTextual()).isTrue();
        assertThat(tree.get("totalAmount").asText()).isEqualTo("9999999999999999.99");
        assertThat(tree.get("items").get(0).get("unitPrice").isTextual()).isTrue();
        assertThat(tree.get("payments").get(0).get("amount").isTextual()).isTrue();
    }

    @Test
    void acceptsMultipleSkusOfSameModelAndCalculatesAllAmountsWithoutRounding() {
        prepareCreate();
        var request = request(List.of(item(10L, 2, "100.00", "5.00", "2.00"),
                item(11L, 3, "50.00", "0.00", "3.00")), "10.00", "5.00", "7.00", "100.00", "300.00");
        service.create(request);
        ArgumentCaptor<List<PiCalculatedItem>> rows = ArgumentCaptor.forClass(List.class);
        verify(mapper).insertItems(eq(1L), rows.capture());
        assertThat(rows.getValue()).hasSize(2);
        assertThat(rows.getValue().get(0).skuId()).isEqualTo(10L);
        assertThat(rows.getValue().get(1).skuId()).isEqualTo(11L);
        assertThat(rows.getValue()).extracting(PiCalculatedItem::productModelId).containsExactly(4L, 4L);
        assertThat(rows.getValue().get(0).lineTotal()).isEqualByComparingTo("197.00");
        verify(mapper).insertPi(anyString(), eq(request), eq("DOMESTIC"), eq("CNY"),
                eq(money("350.00")), eq(money("15.00")), eq(money("10.00")), eq(money("7.00")),
                eq(money("352.00")), eq(money("100.00")), eq(money("300.00")), any(), isNull(), isNull());
    }

    @Test
    void otherFeesAreIncludedInExactTotalAndUnitIsSaved() {
        prepareCreate();
        var request=new PiCreateRequest(1L,null,null,"DOMESTIC","CNY",null,money("1.00"),money("0.03"),money("2.00"),
                money("0.00"),money("0.00"),null,null,List.of(new PiItemRequest(10L,2,money("100.10"),null,null,"PCS",null)),money("3.07"));
        service.create(request);
        verify(mapper).insertPi(anyString(),eq(request),eq("DOMESTIC"),eq("CNY"),eq(money("200.20")),eq(money("1.00")),eq(money("0.03")),eq(money("2.00")),eq(money("204.30")),eq(money("0.00")),eq(money("0.00")),any(),isNull(),isNull());
        ArgumentCaptor<List<PiCalculatedItem>> rows=ArgumentCaptor.forClass(List.class);verify(mapper).insertItems(eq(1L),rows.capture());
        assertThat(rows.getValue().getFirst().unit()).isEqualTo("PCS");
    }

    @Test
    void fixedSkuSourceOrderRejectsAnotherSkuOfTheSameModel() {
        prepareCreate();
        var skuInventory=mock(com.bike.server.stock.SkuInventoryMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"skuInventory",skuInventory);
        when(mapper.lockOrder(7L)).thenReturn(7L);
        when(mapper.countOrder(7L,1L,null)).thenReturn(1);
        when(mapper.selectOrderLines(7L)).thenReturn(List.of(new PiOrderLine(4L,1,0)));
        when(skuInventory.reservationItems(7L)).thenReturn(List.of(new com.bike.server.stock.SkuReservationItem(10L,1,0,0)));
        var wrong=new PiCreateRequest(1L,null,7L,"DOMESTIC","CNY",null,null,null,null,
                money("0"),money("0"),null,null,List.of(item(11L,1,"100",null,null)));
        assertCode(()->service.create(wrong),"ORDER_SKU_MISMATCH");
        verify(mapper,never()).insertItems(anyLong(),any());
        var correct=new PiCreateRequest(1L,null,7L,"DOMESTIC","CNY",null,null,null,null,
                money("0"),money("0"),null,null,List.of(item(10L,1,"100",null,null)));
        assertThatCode(()->service.create(correct)).doesNotThrowAnyException();
    }

    @Test
    void rejectsFractionalCentsNegativeValuesAndMissingExplicitGatesBeforeInsert() {
        prepareCreate();
        for (String invalid : List.of("0.001", "-1.00", "10000000000000000.00")) {
            assertThatThrownBy(() -> service.create(request(List.of(item(10L, 1, invalid, null, null)),
                    null, null, null, "0.00", "0.00"))).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> service.create(request(List.of(item(10L, 1, "1.00", null, null)),
                null, null, null, null, "0.00"))).isInstanceOf(BusinessException.class);
        verify(mapper, never()).insertItems(anyLong(), any());
    }

    @Test
    void rejectsQuantityMultiplicationOverflowBeforePersistence() {
        prepareCreate();
        assertCode(() -> service.create(request(List.of(item(10L, 2, "9999999999999999.99", null, null)),
                null, null, null, "0.00", "0.00")), "AMOUNT_INVALID");
        verify(mapper, never()).insertItems(anyLong(), any());
    }

    @Test
    void rejectsDuplicateSkuAndOverDiscount() {
        prepareCreate();
        assertCode(() -> service.create(request(List.of(item(10L, 1, "10", null, null),
                item(10L, 1, "10", null, null)), null, null, null, "0", "0")), "DUPLICATE_SKU");
        assertCode(() -> service.create(request(List.of(item(10L, 1, "10", "11", null)),
                null, null, null, "0", "0")), "AMOUNT_INVALID");
    }

    @Test
    void rejectsDisabledSkuRatherThanInferringSkuFromModel() {
        prepareCreate();
        assertCode(() -> service.create(request(List.of(item(12L, 1, "10", null, null)),
                null, null, null, "0", "0")), "SKU_NOT_FOUND");
    }

    @Test
    void pdaAndAgentCannotMutateEvenWhenExplicitlyGrantedFinancialPermissions() {
        for (String role : List.of("ROLE_PDA_OPERATOR", "ROLE_WEB_AGENT")) {
            login(role, "PERM_PI_WRITE", "PERM_PI_PAYMENT_CONFIRM", "PERM_PI_RELEASE", "PERM_PI_LOGISTICS_WRITE");
            assertThatThrownBy(() -> service.create(null)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.confirm(1)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.recordPayment(1, null)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.release(1, null)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.addLogistics(1, null)).isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void pdaCannotReadPiEvenWithReadPermission() {
        login("ROLE_PDA_OPERATOR", "PERM_PI_READ");
        assertThatThrownBy(() -> service.get(1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.list(null, null, 1, 20)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void agentCannotSeeOwnDraftOrAnotherAgentsPublishedPiOrPaymentVouchers() {
        login("ROLE_WEB_AGENT", "PERM_PI_READ");
        var authentication = (UsernamePasswordAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        authentication.setDetails(new UserAuthRecord(3L, "agent", "", "WEB_AGENT", 9L, null, true));
        PiView pi = pi("DRAFT");
        pi.setAgentId(9L);
        when(mapper.selectById(1L)).thenReturn(pi);
        assertCode(() -> service.get(1), "FORBIDDEN");
        pi.setStatus("CONFIRMED");
        pi.setAgentId(10L);
        assertCode(() -> service.get(1), "FORBIDDEN");
        pi.setAgentId(9L);
        assertThat(service.get(1).getPayments()).isEmpty();
        verify(mapper, never()).selectPayments(anyLong());
        service.list(null, null, Long.MAX_VALUE, 200);
        verify(mapper).selectPage(null, null, 9L, 999_999_900L, 100L);
    }

    @Test
    void paymentUsesLockAndItsOwnPermissionWithoutRequiringReadPermission() {
        login("ROLE_FINANCE", "PERM_PI_PAYMENT_CONFIRM");
        PiView pi = prepareLocked("CONFIRMED");
        when(mapper.paymentTotal(1L)).thenReturn(money("0.00"));
        when(mapper.insertPayment(eq(1L), any(), isNull())).thenReturn(1);
        when(mapper.syncSeriesPaymentStatus(1L, money("30.00"))).thenReturn(1);
        service.recordPayment(1, payment("request-a", "30.00"));
        var order = inOrder(mapper);
        order.verify(mapper).lockPi(1L);
        order.verify(mapper).selectForUpdate(1L);
        order.verify(mapper).selectPaymentByRequest(1L, "request-a");
        order.verify(mapper).paymentTotal(1L);
        order.verify(mapper).insertPayment(eq(1L), any(), isNull());
        verify(mapper).syncSeriesPaymentStatus(1L, money("30.00"));
    }

    @Test
    void paymentRequestRetryIsIdempotentAndConflictingReuseIsRejected() {
        prepareLocked("RELEASED");
        when(mapper.selectPaymentByRequest(1L, "same")).thenReturn(new PiPaymentView(5L, 1L, "same",
                money("30.00"), "CNY", null, "CONFIRMED", LocalDateTime.now(), null, null));
        service.recordPayment(1, payment("same", "30.00"));
        assertCode(() -> service.recordPayment(1, payment("same", "31.00")), "PAYMENT_REQUEST_CONFLICT");
        verify(mapper, never()).insertPayment(anyLong(), any(), any());
    }

    @Test
    void reachingDepositDoesNotSatisfyIndependentShipmentGate() {
        PiView pi = prepareLocked("CONFIRMED");
        pi.setPaymentStatus("DEPOSIT_MET");
        when(mapper.paymentTotal(1L)).thenReturn(money("30.00"));
        assertCode(() -> service.release(1, new PiReleaseRequest("已核对订单条件")), "PAYMENT_GATE_NOT_MET");
        verify(mapper, never()).updateStatus(anyLong(), any(), any(), any(), anyBoolean());
    }

    @Test
    void releaseUsesActualPaymentTotalAndPersistsReason() {
        prepareLocked("CONFIRMED");
        when(mapper.paymentTotal(1L)).thenReturn(money("100.00"));
        when(mapper.updateStatus(1L, "CONFIRMED", "RELEASED", null, true)).thenReturn(1);
        service.release(1, new PiReleaseRequest("  已核对交付条款  "));
        verify(logs).insert(eq("PI"), eq("1"), eq("放行 PI 发货"), isNull(), eq("pi-test"),
                contains("CONFIRMED"), contains("shipmentRequiredAmount"), isNull(), eq("已核对交付条款"));
    }

    @Test
    void releasedPiStillAcceptsBalancePayment() {
        prepareLocked("RELEASED");
        when(mapper.paymentTotal(1L)).thenReturn(money("30.00"));
        when(mapper.insertPayment(eq(1L), any(), isNull())).thenReturn(1);
        when(mapper.syncSeriesPaymentStatus(1L, money("100.00"))).thenReturn(1);
        service.recordPayment(1, payment("balance", "70.00"));
        verify(mapper).syncSeriesPaymentStatus(1L, money("100.00"));
    }

    @Test
    void cannotPublishWithoutSavedCommercialTermsAndTemplateIdentity() {
        prepareLocked("DRAFT");
        assertCode(() -> service.confirm(1), "PI_DOCUMENT_INCOMPLETE");
        verify(mapper, never()).updateStatus(anyLong(), any(), any(), any(), anyBoolean());
    }

    @Test
    void cannotClaimShippedLogisticsBeforeReleaseOrWithoutCarrierAndTracking() {
        prepareLocked("CONFIRMED");
        assertCode(() -> service.addLogistics(1, new PiLogisticsRequest("batch", "carrier", "track", "SHIPPED", null)),
                "LOGISTICS_NOT_READY");
        PiView pi = prepareLocked("RELEASED");
        pi.setReleaseStatus("RELEASED");
        assertCode(() -> service.addLogistics(1, new PiLogisticsRequest("batch", null, "track", "SHIPPED", null)),
                "LOGISTICS_NOT_READY");
        verify(mapper, never()).insertLogistics(anyLong(), any(), any());
    }

    private void prepareCreate() {
        when(mapper.countEnabledCustomer(1L)).thenReturn(1);
        when(mapper.selectEnabledSku(10L)).thenReturn(new PiSkuSnapshot(10L, 4L, "RED", "红色电动车", "红色", "City"));
        when(mapper.selectEnabledSku(11L)).thenReturn(new PiSkuSnapshot(11L, 4L, "BLUE", "蓝色电动车", "蓝色", "City"));
        when(mapper.insertPi(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(mapper.selectInsertedId()).thenReturn(1L);
        when(mapper.insertItems(anyLong(), any())).thenAnswer(call -> ((List<?>) call.getArgument(1)).size());
        when(mapper.selectById(1L)).thenReturn(pi("DRAFT"));
    }

    private PiView prepareLocked(String status) {
        PiView pi = pi(status);
        when(mapper.lockPi(1L)).thenReturn(1L);
        when(mapper.selectById(1L)).thenReturn(pi);
        when(mapper.selectForUpdate(1L)).thenReturn(pi);
        return pi;
    }

    static PiView pi(String status) {
        PiView pi = new PiView();
        pi.setId(1L);
        pi.setPiNo("PI-1");
        pi.setStatus(status);
        pi.setPaymentStatus("UNPAID");
        pi.setReleaseStatus("BLOCKED");
        pi.setCurrencyCode("CNY");
        pi.setTotalAmount(money("100.00"));
        pi.setDepositRequiredAmount(money("30.00"));
        pi.setShipmentRequiredAmount(money("100.00"));
        return pi;
    }

    static PiCreateRequest request(List<PiItemRequest> items, String discount, String tax, String freight, String deposit, String shipment) {
        return new PiCreateRequest(1L, null, null, "DOMESTIC", "CNY", null,
                money(discount), money(tax), money(freight), money(deposit), money(shipment), null, null, items);
    }

    static PiItemRequest item(Long sku, int quantity, String price, String discount, String tax) {
        return new PiItemRequest(sku, quantity, money(price), money(discount), money(tax));
    }

    static PiPaymentRequest payment(String request, String amount) {
        return new PiPaymentRequest(request, money(amount), "CNY", null, null, null);
    }

    static BigDecimal money(String value) { return value == null ? null : new BigDecimal(value); }

    static void login(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "pi-test", null, java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(code));
    }
}
