package com.bike.server.order;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.pi.PiMapper;
import com.bike.server.pi.PiView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderReservationService {
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.common.CustomerScopeAccess customerScope;
    private final OrderMapper orders;
    private final OrderReservationMapper reservations;
    private final OrderReservationCoordinator coordinator;
    private final PiMapper pis;
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.stock.SkuInventoryGuard skuInventoryGuard;
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.pi.PiSeriesMapper series;

    public OrderReservationService(OrderMapper orders, OrderReservationMapper reservations,
            OrderReservationCoordinator coordinator, PiMapper pis) {
        this.orders = orders;
        this.reservations = reservations;
        this.coordinator = coordinator;
        this.pis = pis;
    }

    private SalesOrderView require(long id) {
        CurrentOperator.requireInternalWebAccess();
        CurrentOperator.requirePermission("ORDER_WRITE");
        coordinator.lockInventory();
        SalesOrderView order = orders.selectForUpdate(id);
        if (order == null) throw new BusinessException("ORDER_NOT_FOUND", "订单不存在");
        customerScope.requireCustomer(order.getCustomerId());
        if (java.util.Set.of("CANCELLED", "CLOSED").contains(order.getStatus())) {
            throw new BusinessException("INVALID_STATUS_TRANSITION", "已关闭订单不能重新占用或关闭");
        }
        return order;
    }

    @Transactional
    public SalesOrderView renew(long id, int hours, String reason) {
        CurrentOperator.requirePermission("AGENT_LOCK_WRITE");
        if (hours < 1 || hours > 720 || reason == null || reason.isBlank() || reason.length() > 500) {
            throw new BusinessException("INVALID_PARAMETER", "续占用需1至720小时及有效原因");
        }
        SalesOrderView order = require(id);
        OrderReservationView reservation = reservations.selectForUpdate(id);
        if (reservation == null) throw new BusinessException("ORDER_NOT_RESERVED", "此订单没有来源锁定占用");
        Long currentPi = series == null ? null : series.currentPiForOrder(id);
        PiView pi = currentPi == null ? pis.selectForSourceOrderForUpdate(id) : pis.selectForUpdate(currentPi);
        if ("RELEASED".equals(reservation.holdMode()) || pi != null && "CANCELLED".equals(pi.getStatus())) {
            throw new BusinessException("ORDER_RESERVATION_RELEASED", "订单或PI已主动释放占用，不能通过续占用重新打开");
        }
        int remaining = order.getOrderQty() - order.getShippedQty();
        if (remaining < 0 || remaining == 0 && !"SHIPPED".equals(order.getStatus())) {
            throw new BusinessException("INVALID_STATUS_TRANSITION", "订单没有可处理的剩余数量");
        }
        // A paid hold remains paid; a legacy renewal request must not turn it back into a timed hold.
        if ("PAID".equals(reservation.holdMode())) return order;
        if (reservations.snapshot(order.getModelId(), id).availableForOrder() < remaining) {
            throw new BusinessException("INVENTORY_NOT_ENOUGH", "当前可用库存不足，不能重新占用；已登记的收款保持不变");
        }
        if(skuInventoryGuard!=null)skuInventoryGuard.requireReacquire(id);
        if (reservations.renew(id, hours) != 1) throw new BusinessException("ORDER_UPDATE_FAILED", "占用更新失败");
        if (pi != null && java.util.Set.of("CONFIRMED", "RELEASED").contains(pi.getStatus())) {
            coordinator.retainOrderAfterPayment(pi, id, pis.paymentTotal(pi.getId()));
        }
        OrderReservationView result = reservations.selectForUpdate(id);
        String remark = reason.trim() + "；占用状态：" + result.reservationStatus() + "；本次截止：" + result.expiresAt();
        if (orders.insertOperationLog("ORDER_UPDATE", order.getOrderNo(), "重新确认库存占用", CurrentOperator.id(),
                CurrentOperator.username(), order.getStatus(), order.getStatus(), CurrentOperator.clientIp(),
                remark.substring(0, Math.min(500, remark.length()))) != 1) {
            throw new BusinessException("ORDER_AUDIT_FAILED", "库存占用审计保存失败");
        }
        return orders.selectById(id);
    }

    @Transactional
    public SalesOrderView closeRemainder(long id, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new BusinessException("INVALID_PARAMETER", "关闭剩余数量必须填写原因");
        }
        SalesOrderView order = require(id);
        if (!"PARTIAL_SHIPPED".equals(order.getStatus()) || order.getShippedQty() <= 0
                || order.getShippedQty() >= order.getOrderQty()) {
            throw new BusinessException("INVALID_STATUS_TRANSITION", "仅部分出库订单可关闭剩余数量");
        }
        coordinator.release(id, "关闭剩余数量：" + reason.trim());
        if (orders.updateStatus(id, "CLOSED") != 1) throw new BusinessException("ORDER_UPDATE_FAILED", "关闭剩余数量失败");
        if (orders.insertOperationLog("ORDER_UPDATE", order.getOrderNo(), "关闭剩余未发货数量", CurrentOperator.id(),
                CurrentOperator.username(), order.getStatus(), "CLOSED", CurrentOperator.clientIp(), reason.trim()) != 1) {
            throw new BusinessException("ORDER_AUDIT_FAILED", "关闭剩余数量审计保存失败");
        }
        return orders.selectById(id);
    }
}
