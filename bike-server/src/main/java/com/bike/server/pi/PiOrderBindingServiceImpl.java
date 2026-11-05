package com.bike.server.pi;

import com.bike.server.common.BusinessException;
import com.bike.server.order.OrderMapper;
import com.bike.server.order.OrderReservationMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PiOrderBindingServiceImpl implements PiOrderBindingService {
    private final PiMapper pis;
    private final PiSeriesMapper series;
    private final OrderMapper orders;
    private final OrderReservationMapper reservations;
    private final PiBusinessMapper warehouses;
    private final ObjectMapper json;

    public PiOrderBindingServiceImpl(PiMapper pis, PiSeriesMapper series, OrderMapper orders,
            OrderReservationMapper reservations, PiBusinessMapper warehouses, ObjectMapper json) {
        this.pis = pis;
        this.series = series;
        this.orders = orders;
        this.reservations = reservations;
        this.warehouses = warehouses;
        this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireConfirmableBinding(PiView pi) {
        List<Long> orderIds = series.orderIds(pi.getId());
        requireSourceLink(pi, orderIds);
        if (orderIds.isEmpty()) {
            // 旧草稿仍可编辑；只有明确选择无原订单的新业务，才能进入收款后建单流程。
            PiDocumentSnapshot document = document(pi);
            if (document == null || document.business() == null
                    || !"NEW_BUSINESS".equals(document.business().orderSource())) {
                fail("PI_ORDER_SOURCE_REQUIRED", "请先关联来源订单；确无原订单的新业务，请在编辑草稿时明确选择无原订单的新业务");
            }
            return;
        }
        requireConsistentFulfillment(pi);
        requireWarehouseCapacity(pi);
        if (pi.getPreviousRevisionId() == null) {
            for (long orderId : orderIds) {
                var order = orders.selectForUpdate(orderId);
                if (!Set.of("DRAFT", "PENDING", "CONFIRMED").contains(order.getStatus()) || order.getShippedQty() > 0)
                    fail("PI_ORDER_STATUS_INVALID", "来源订单 " + order.getOrderNo() + " 已关闭或已有出库，不能确认首次PI");
                if (reservations.expiredForOrder(orderId) > 0)
                    fail("ORDER_RESERVATION_EXPIRED", "来源订单 " + order.getOrderNo() + " 占用已到期或释放，请先重新办理占用");
            }
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireConsistentFulfillment(PiView pi) {
        List<Long> orderIds = series.orderIds(pi.getId());
        requireSourceLink(pi, orderIds);
        if (orderIds.isEmpty()) fail("PI_ORDER_LINK_REQUIRED", "PI尚未关联履约订单，请先完成收款库存占用流程");
        for (long orderId : orderIds) {
            var order = orders.selectForUpdate(orderId);
            if (order == null) fail("ORDER_NOT_FOUND", "PI关联的履约订单不存在");
            if (!Objects.equals(order.getCustomerId(), pi.getCustomerId()))
                fail("PI_ORDER_CUSTOMER_MISMATCH", "PI与来源订单 " + order.getOrderNo() + " 的客户不一致");
            if (!Objects.equals(order.getAgentId(), pi.getAgentId()))
                fail("PI_ORDER_AGENT_MISMATCH", "PI与来源订单 " + order.getOrderNo() + " 的代理商不一致，请以原订单为准");
        }
        Map<Long, Long> expected = new TreeMap<>(), actual = new TreeMap<>();
        for (var item : pis.selectItems(pi.getId())) expected.merge(item.getSkuId(), (long) item.getQuantity(), Long::sum);
        for (var line : series.fulfillment(pi.getId())) {
            long quantity = (long) line.shippedQty() + line.remainingQty();
            if (line.remainingQty() < 0) fail("PI_ORDER_ITEMS_MISMATCH", "履约订单剩余数量异常，请先核对");
            if (quantity == 0) continue;
            if (line.skuId() == null) fail("PI_ORDER_SKU_REQUIRED", "来源订单 " + line.orderNo() + " 缺少精确SKU，须先补齐履约明细");
            actual.merge(line.skuId(), quantity, Long::sum);
        }
        if (expected.isEmpty() || !expected.equals(actual))
            fail("PI_ORDER_ITEMS_MISMATCH", "PI的SKU及数量与关联订单不一致，请以原订单明细为准");
    }

    private void requireSourceLink(PiView pi, List<Long> orderIds) {
        if (pi.getSourceOrderId() != null && !orderIds.contains(pi.getSourceOrderId()))
            fail("PI_ORDER_LINK_REQUIRED", "PI来源订单尚未建立完整履约关联，请先核对单据关联");
    }

    private void requireWarehouseCapacity(PiView pi) {
        var document = document(pi);
        if (document == null || document.business() == null || document.business().warehouseId() == null) return;
        long warehouseId = document.business().warehouseId();
        Map<Long, Long> remaining = new TreeMap<>();
        for (var line : series.fulfillment(pi.getId()))
            if (line.remainingQty() > 0) remaining.merge(line.skuId(), (long) line.remainingQty(), Long::sum);
        for (var entry : remaining.entrySet()) {
            long available = (long) warehouses.countWarehouseStock(warehouseId, entry.getKey())
                    - warehouses.countWarehouseReserved(warehouseId, entry.getKey(), Objects.requireNonNullElse(pi.getSeriesId(), 0L));
            if (available < entry.getValue()) fail("PI_WAREHOUSE_STOCK_SHORTAGE",
                    "所选出库仓库的SKU " + entry.getKey() + " 可用库存为 " + Math.max(0, available)
                            + " 台，原订单待发 " + entry.getValue() + " 台；请选择库存充足的仓库或先完成入库");
        }
    }

    private PiDocumentSnapshot document(PiView pi) {
        try {
            return pi.getDocumentJson() == null ? null : json.readValue(pi.getDocumentJson(), PiDocumentSnapshot.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("PI业务来源资料读取失败，piId=" + pi.getId(), error);
        }
    }

    private static void fail(String code, String message) { throw new BusinessException(code, message); }
}
