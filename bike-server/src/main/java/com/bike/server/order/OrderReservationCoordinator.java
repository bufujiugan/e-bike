package com.bike.server.order;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.common.OperationLogMapper;
import com.bike.server.pi.PiView;
import com.bike.server.production.ProductionGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Inventory writers acquire the shared mutex, then order/reservation rows, then the PI row. */
@Component
public class OrderReservationCoordinator {
    private final ProductionGuard guard;
    private final OrderReservationMapper reservations;
    private final OperationLogMapper logs;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.stock.SkuInventoryMapper skuInventory;
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.pi.PiSeriesMapper series;

    public OrderReservationCoordinator(ProductionGuard guard, OrderReservationMapper reservations,
            OperationLogMapper logs, ObjectMapper json) {
        this.guard = guard;
        this.reservations = reservations;
        this.logs = logs;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockInventory() { guard.lock(); }

    @Transactional(propagation = Propagation.MANDATORY)
    public OrderReservationView lockReservation(long orderId) {
        return reservations.selectForUpdate(orderId);
    }

    /** Receipt facts commit even when the former reservation has already expired. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String retainAfterPayment(PiView pi, BigDecimal paidAmount) {
        if (series != null) {
            var states = new java.util.TreeSet<String>();
            for (long orderId : series.orderIds(pi.getId())) states.add(retainOrderAfterPayment(pi, orderId, paidAmount));
            return states.isEmpty() ? "NONE" : states.size()==1 ? states.first() : "MIXED";
        }
        if (pi.getSourceOrderId() == null) return "NONE";
        return retainOrderAfterPayment(pi, pi.getSourceOrderId(), paidAmount);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String retainOrderAfterPayment(PiView pi, long orderId, BigDecimal paidAmount) {
        OrderReservationView before = reservations.selectForUpdate(orderId);
        if (before == null) return "NONE";
        if (paidAmount.compareTo(pi.getDepositRequiredAmount()) < 0
                || !"TIMED".equals(before.holdMode())
                || before.remainingQuantity() == 0
                || java.util.Set.of("CANCELLED", "CLOSED").contains(before.orderStatus())) {
            return before.reservationStatus();
        }
        // NOW(3) is evaluated in this statement, after all conflicting locks have been obtained.
        int held = series == null ? reservations.holdAfterPayment(before.orderId(), pi.getId(), paidAmount, CurrentOperator.id())
                : series.holdAfterPayment(before.orderId(), pi.getId(), paidAmount, CurrentOperator.id());
        OrderReservationView after = reservations.selectForUpdate(before.orderId());
        if (held == 1) {
            audit(before.orderId(), "收款达标保留订单占用", before.reservationStatus(), after.reservationStatus(),
                    pi.getId(), paidAmount, "保留剩余数量：" + after.remainingQuantity() + "；待履约或人工关闭");
        } else if ("EXPIRED_REQUIRES_REACQUIRE".equals(after.reservationStatus())) {
            audit(before.orderId(), "收款已达条件但占用已到期", before.reservationStatus(), after.reservationStatus(),
                    pi.getId(), paidAmount, "已保存到账事实；须人工重新核对库存并办理占用，未自动恢复");
        }
        return after.reservationStatus();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(long orderId, String reason) {
        OrderReservationView before = reservations.selectForUpdate(orderId);
        if (before == null || "RELEASED".equals(before.holdMode())) return;
        if (reservations.release(orderId) != 1) throw new BusinessException("ORDER_UPDATE_FAILED", "订单占用释放失败");
        if(skuInventory!=null)skuInventory.closeRemainder(orderId);
        audit(orderId, "主动释放订单剩余占用", before.reservationStatus(), "RELEASED", before.paymentHoldPiId(),
                null, reason + "；释放未发数量：" + before.remainingQuantity());
    }

    private void audit(long orderId, String action, String before, String after, Long piId,
            BigDecimal paidAmount, String reason) {
        var details = new java.util.LinkedHashMap<String, Object>();
        details.put("reservationStatus", after);
        details.put("piId", piId);
        details.put("paidAmount", paidAmount);
        String remark = reason.length() > 500 ? reason.substring(0, 500) : reason;
        try {
            if (logs.insert("ORDER_RESERVATION", Long.toString(orderId), action, CurrentOperator.id(),
                    CurrentOperator.username(), json.writeValueAsString(Map.of("reservationStatus", before)),
                    json.writeValueAsString(details), CurrentOperator.clientIp(), remark) != 1) {
                throw new BusinessException("ORDER_AUDIT_FAILED", "订单占用审计保存失败");
            }
        } catch (JsonProcessingException error) {
            throw new BusinessException("ORDER_AUDIT_FAILED", "订单占用审计无法保存");
        }
    }
}
