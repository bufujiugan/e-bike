package com.bike.server.pi;

import com.bike.server.auth.UserAuthRecord;
import com.bike.server.common.*;
import com.bike.server.order.*;
import com.bike.server.stock.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PiBusinessServiceTest {
    private final PiBusinessMapper mapper = mock(PiBusinessMapper.class);
    private final PiMapper pis = mock(PiMapper.class);
    private final PiSeriesMapper series = mock(PiSeriesMapper.class);
    private final PiWorkflowMapper workflow = mock(PiWorkflowMapper.class);
    private final OrderMapper orders = mock(OrderMapper.class);
    private final OrderReservationMapper reservations = mock(OrderReservationMapper.class);
    private final SkuInventoryMapper inventory = mock(SkuInventoryMapper.class);
    private final OperationLogMapper logs = mock(OperationLogMapper.class);
    private final PaymentPriorityService priority = mock(PaymentPriorityService.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final PiBusinessServiceImpl service = new PiBusinessServiceImpl(mapper,pis,series,workflow,orders,reservations,inventory,logs,json);
    private PiView pi;
    private PiItemView item;

    @BeforeEach void setup() {
        org.springframework.test.util.ReflectionTestUtils.setField(service,"paymentPriority",priority);
        var user = new UserAuthRecord(1L,"pi-test","unused","INTERNAL",null,null,true);
        var authentication = new UsernamePasswordAuthenticationToken(user.username(),null,List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        authentication.setDetails(user);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        pi = new PiView(); pi.setId(1L); pi.setSeriesId(1L); pi.setCustomerId(10L); pi.setTradeType("DOMESTIC");
        pi.setPaidAmount(new BigDecimal("100")); pi.setShipmentRequiredAmount(new BigDecimal("100"));
        pi.setDepositRequiredAmount(new BigDecimal("50"));
        item = new PiItemView(); item.setSkuId(20L); item.setProductModelId(30L); item.setQuantity(5);
        when(pis.selectById(1L)).thenReturn(pi);
        when(pis.selectItems(1L)).thenReturn(List.of(item));
        when(mapper.selectDocument(1L)).thenReturn("""
            {"bankName":"Bank","bankAccount":"Account","beneficiary":"Guangta",
             "business":{"warehouseId":2,"domesticShipment":true,"collectionEntity":"GUANGTA"}}
            """);
        when(mapper.countEnabledWarehouse(2L)).thenReturn(1);
        when(logs.insert(any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(1);
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    private PiFulfillmentProfileMapper configureCredit(boolean approved) throws Exception {
        var profiles=mock(PiFulfillmentProfileService.class);
        var credit=mock(PiFulfillmentProfileMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"profiles",profiles);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"profileMapper",credit);
        when(profiles.resolveProfile(any())).thenReturn(PiFulfillmentProfileTest.profile());
        pi.setPaidAmount(BigDecimal.ZERO);
        if(approved)when(workflow.selectSteps(1L)).thenReturn(List.of(new PiWorkflowStepRecord(13,"CREDIT_APPROVED","授权","COMPLETED",1L,LocalDateTime.now(),"合同批准", "{}")));
        return credit;
    }
    @Test void unapprovedCreditCannotReserveEvenWhenDepositHasBeenPaid() throws Exception {
        configureCredit(false);
        assertThat(service.reserveAfterPayment(pi,new BigDecimal("100"))).isFalse();
        verifyNoInteractions(orders,reservations,inventory);
    }
    @Test void creditApprovalCreatesProtectedReservationWithoutInventingPayment() throws Exception {
        var credit=configureCredit(true);
        when(mapper.countWarehouseStock(2L,20L)).thenReturn(5);
        when(inventory.selectFulfillmentSnapshot(20L,0L)).thenReturn(new SkuInventorySnapshot(5,0,0));
        var model=new OrderInventorySnapshot();model.setPhysicalQty(5);
        when(reservations.selectFulfillmentSnapshot(30L,0L)).thenReturn(model);
        var order=new SalesOrderView();order.setId(4L);
        when(orders.selectByNo(anyString())).thenReturn(order);
        when(orders.insertOrder(anyString(),any(),any())).thenReturn(1);when(orders.insertItem(anyString(),any())).thenReturn(1);
        when(credit.insertCreditSource(eq(1L),eq(20L),eq(5),anyString(),any())).thenReturn(1);
        when(mapper.selectLockId(anyString())).thenReturn(9L);
        when(credit.insertCreditReservation(eq(4L),eq(9L),eq(1L),any())).thenReturn(1);
        when(inventory.insertReservationItem(4L,20L,5)).thenReturn(1);
        when(series.link(eq(1L),eq(4L),anyString())).thenReturn(1);when(orders.updateStatus(4L,"CONFIRMED")).thenReturn(1);
        assertThat(service.reserveAfterPayment(pi,BigDecimal.ZERO)).isTrue();
        verify(credit).insertCreditReservation(eq(4L),eq(9L),eq(1L),any());
        verify(mapper,never()).insertPaidReservation(anyLong(),anyLong(),anyLong(),any());
        verifyNoInteractions(priority);
        assertThat(pi.getPaidAmount()).isZero();
    }
    @Test void creditCannotBypassWarehouseShortage() throws Exception {
        var credit=configureCredit(true);
        when(mapper.countWarehouseStock(2L,20L)).thenReturn(4);
        assertThat(service.reserveAfterPayment(pi,BigDecimal.ZERO)).isFalse();
        verifyNoInteractions(credit);
    }

    @Test void paymentBelowContractThresholdNeverCreatesOrders() {
        assertThat(service.reserveAfterPayment(pi,new BigDecimal("30"))).isFalse();
        verifyNoInteractions(orders,reservations,inventory);
    }
    @Test void warehouseShortageDoesNotCreatePartialFulfillment() {
        when(mapper.countWarehouseStock(2L,20L)).thenReturn(4);
        assertThat(service.reserveAfterPayment(pi,pi.getPaidAmount())).isFalse();
        verify(orders,never()).insertOrder(any(),any(),any());
        verify(mapper,never()).insertPaidSource(anyLong(),anyLong(),anyInt(),anyString(),any());
    }
    @Test void mismatchedFulfillmentQuantityIsNotReserved() {
        when(series.fulfillment(1L)).thenReturn(List.of(line(4,0,"PAID_HELD")));
        assertThat(service.hasReservedStock(pi)).isFalse();
        assertThat(service.reserveAfterPayment(pi,pi.getPaidAmount())).isFalse();
    }
    @Test void paidReservationRetryDoesNotCreateDuplicateOrders() {
        when(series.fulfillment(1L)).thenReturn(List.of(line(5,0,"PAID_HELD")));
        when(mapper.countWarehouseStock(2L,20L)).thenReturn(5);
        when(reservations.selectForUpdate(4L)).thenReturn(new OrderReservationView(4L,"SO4","CONFIRMED","PAID","PAID_HELD",LocalDateTime.now(),5,0,1L,null,null));
        var order = new SalesOrderView(); order.setId(4L); order.setModelId(30L);
        when(orders.selectForUpdate(4L)).thenReturn(order);
        when(inventory.selectFulfillmentSnapshot(20L,0L)).thenReturn(new SkuInventorySnapshot(5,5,0));
        var model = new OrderInventorySnapshot(); model.setPhysicalQty(5); model.setCommittedQty(5);
        when(reservations.selectFulfillmentSnapshot(30L,0L)).thenReturn(model);
        var owned = new OrderInventorySnapshot(); owned.setOwnedQty(5);
        when(reservations.selectFulfillmentSnapshot(30L,4L)).thenReturn(owned);
        assertThat(service.reserveAfterPayment(pi,pi.getPaidAmount())).isTrue();
        assertThat(service.reserveAfterPayment(pi,pi.getPaidAmount())).isTrue();
        assertThat(service.hasReservedStock(pi)).isTrue();
        verify(orders,never()).insertOrder(any(),any(),any());
        verify(mapper,never()).updatePaidReservation(anyLong(),anyLong(),any());
    }
    @Test void ordinaryPiCreatesPaidFulfillmentAfterSufficientPayment() {
        when(mapper.countWarehouseStock(2L,20L)).thenReturn(5);
        when(inventory.selectFulfillmentSnapshot(20L,0L)).thenReturn(new SkuInventorySnapshot(5,0,0));
        var model = new OrderInventorySnapshot(); model.setPhysicalQty(5);
        when(reservations.selectFulfillmentSnapshot(30L,0L)).thenReturn(model);
        var order = new SalesOrderView(); order.setId(4L);
        when(orders.selectByNo(anyString())).thenReturn(order);
        when(orders.insertOrder(anyString(),any(),any())).thenReturn(1);
        when(orders.insertItem(anyString(),any())).thenReturn(1);
        when(mapper.insertPaidSource(eq(1L),eq(20L),eq(5),anyString(),any())).thenReturn(1);
        when(mapper.selectLockId(anyString())).thenReturn(9L);
        when(mapper.insertPaidReservation(eq(4L),eq(9L),eq(1L),any())).thenReturn(1);
        when(inventory.insertReservationItem(4L,20L,5)).thenReturn(1);
        when(series.link(1L,4L,"PAID_PI")).thenReturn(1);
        when(orders.updateStatus(4L,"CONFIRMED")).thenReturn(1);
        assertThat(service.reserveAfterPayment(pi,pi.getPaidAmount())).isTrue();
        verify(series).link(1L,4L,"PAID_PI");
        verify(priority).cancelUnfulfillableUnpaidOrders(pi);
    }
    @Test void depositReservesBeforeShipmentThresholdWithoutPermittingShipment() {
        when(series.fulfillment(1L)).thenReturn(List.of(line(5,0,"TIMED_ACTIVE")));
        when(mapper.countWarehouseStock(2L,20L)).thenReturn(5);
        when(reservations.selectForUpdate(4L)).thenReturn(new OrderReservationView(4L,"SO4","CONFIRMED","TIMED","TIMED_ACTIVE",LocalDateTime.now(),5,0,null,null,null));
        var order = new SalesOrderView(); order.setId(4L); order.setModelId(30L);
        when(orders.selectForUpdate(4L)).thenReturn(order);
        when(inventory.selectFulfillmentSnapshot(20L,0L)).thenReturn(new SkuInventorySnapshot(5,0,0));
        var model = new OrderInventorySnapshot(); model.setPhysicalQty(5);
        when(reservations.selectFulfillmentSnapshot(30L,0L)).thenReturn(model);
        when(reservations.selectFulfillmentSnapshot(30L,4L)).thenReturn(model);
        when(mapper.updatePaidReservation(eq(4L),eq(1L),any())).thenReturn(1);
        assertThat(service.reserveAfterPayment(pi,new BigDecimal("50"))).isTrue();
        verify(priority).cancelUnfulfillableUnpaidOrders(pi);
        pi.setPaidAmount(new BigDecimal("50"));
        when(series.fulfillment(1L)).thenReturn(List.of(line(5,0,"PAID_HELD")));
        assertThat(service.hasReservedStock(pi)).isTrue();
        documents();
        String shipmentDocument=mapper.selectDocument(1L).replace("true","false");
        when(mapper.selectDocument(1L)).thenReturn(shipmentDocument);
        assertThatThrownBy(()->service.requireShipment(1L)).hasMessageContaining("出货付款门槛");
    }
    @Test void otherPaidOrdersCannotBeDisplacedAndShortageNeverCancelsCompetitors() {
        when(mapper.countWarehouseStock(2L,20L)).thenReturn(5);
        when(mapper.countWarehouseReserved(2L,20L,1L)).thenReturn(1);
        assertThat(service.reserveAfterPayment(pi,new BigDecimal("50"))).isFalse();
        verifyNoInteractions(priority);
        verify(orders,never()).insertOrder(any(),any(),any());
    }
    @Test void domesticShipmentNeedsPurchaseContractRegardlessOfTrade() {
        documents();
        assertThatThrownBy(()->service.requireContract(1L)).isInstanceOf(BusinessException.class).hasMessageContaining("购销合同");
    }
    @Test void foreignShipmentDoesNotNeedDomesticPurchaseContract() {
        documents();
        String document = mapper.selectDocument(1L).replace("true","false");
        when(mapper.selectDocument(1L)).thenReturn(document);
        assertThatCode(()->service.requireContract(1L)).doesNotThrowAnyException();
    }
    @Test void anotherWarehouseVehicleCannotBeShipped() {
        when(mapper.selectVehicleWarehouse(8L)).thenReturn(3L);
        assertThatThrownBy(()->service.requireVehicle(1L,8L)).hasMessageContaining("不属于本PI指定");
    }
    @Test void anotherOrderCannotConsumeWarehouseReservations() {
        when(mapper.selectVehicleWarehouse(8L)).thenReturn(2L);
        when(mapper.selectVehicleSku(8L)).thenReturn(20L);
        when(mapper.countWarehouseStock(2L,20L)).thenReturn(5);
        when(mapper.countWarehouseReserved(2L,20L,0L)).thenReturn(5);
        assertThatThrownBy(()->service.requireWarehouseCapacity(8L,null)).hasMessageContaining("其他PI占用");
    }
    @Test void revisedPaidHoldCannotExceedWarehouseEvenWhenNewPaymentIsInsufficient() {
        pi.setPaidAmount(BigDecimal.ONE);
        when(series.fulfillment(1L)).thenReturn(List.of(line(6,0,"PAID_HELD")));
        when(mapper.countWarehouseStock(2L,20L)).thenReturn(5);
        assertThatThrownBy(()->service.validateReservedWarehouse(pi)).hasMessageContaining("超过指定仓库");
    }
    @Test void partialDispatchCannotCompleteTracking() {
        when(mapper.countActiveShipments(1L)).thenReturn(1);
        when(mapper.countUnshipped(1L)).thenReturn(1);
        assertThat(service.allTracked(1L)).isFalse();
        when(mapper.countUnshipped(1L)).thenReturn(0);
        when(mapper.countUntrackedBatches(1L)).thenReturn(1);
        assertThat(service.allTracked(1L)).isFalse();
        when(mapper.countUntrackedBatches(1L)).thenReturn(0);
        assertThat(service.allTracked(1L)).isTrue();
    }
    @Test void arrivalAttachmentMustBelongToPi() {
        assertThatThrownBy(()->service.requireArrivalProof(1L,"other-attachment")).hasMessageContaining("本PI物流附件");
    }
    @Test void shipmentRequiresReceiptAttachmentAfterContractCompletion() {
        documents();
        String document = mapper.selectDocument(1L).replace("true","false");
        when(mapper.selectDocument(1L)).thenReturn(document);
        when(mapper.countPaymentsWithoutReceipt(1L)).thenReturn(1);
        assertThatThrownBy(()->service.requireShipment(1L)).hasMessageContaining("收款凭证");
    }
    @Test void revisionMustReconfirmSalesContract() {
        pi.setPreviousRevisionId(2L);
        var previous = new PiView(); previous.setId(2L);
        when(pis.selectById(2L)).thenReturn(previous);
        when(workflow.selectSteps(2L)).thenReturn(List.of(new PiWorkflowStepRecord(3,"DOCUMENTS_READY","资料","COMPLETED",1L,null,null,
            """
            {"contractNo":"OLD","contractReady":true,"paymentTableReady":true}
            """)));
        assertThatThrownBy(()->service.requireDocuments(1L)).hasMessageContaining("销售合同");
    }
    @Test void stockCorrectionInvalidatesCurrentSeriesDeliveryConfirmation() {
        when(series.currentPiForOrder(4L)).thenReturn(2L);
        service.invalidateDeliveryForOrder(4L);
        verify(mapper).updateDeliveryPending(2L);
    }
    private void documents() {
        when(workflow.selectSteps(1L)).thenReturn(List.of(new PiWorkflowStepRecord(3,"DOCUMENTS_READY","资料","COMPLETED",1L,null,null,
            """
            {"contractNo":"SALE1","contractReady":true,"paymentTableReady":true}
            """)));
    }
    private PiFulfillmentLine line(int ordered,int shipped,String status) {
        return new PiFulfillmentLine(4L,"SO4","CONFIRMED",20L,"SKU20",ordered,shipped,0,ordered-shipped,status);
    }
}
