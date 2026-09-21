package com.bike.server.order;

import com.bike.server.production.ProductionGuard;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 查询即时判定失效；本任务只补充可追踪的到期事件，不修改订单履约历史。 */
@Component
public class OrderReservationExpiryJob {
    private final OrderReservationMapper reservations;
    private final OrderMapper orders;
    private final ProductionGuard guard;

    public OrderReservationExpiryJob(OrderReservationMapper reservations, OrderMapper orders, ProductionGuard guard) {
        this.reservations=reservations; this.orders=orders; this.guard=guard;
    }

    @Scheduled(fixedDelayString="${bike.inventory-lock.expiry-check-ms:60000}")
    @Transactional
    public void auditExpired() {
        guard.lock();
        for (SalesOrderView order : reservations.expiredForAudit()) {
            if (reservations.markExpiryAudited(order.getId()) != 1) continue;
            if (orders.insertOperationLog("ORDER_RESERVATION_EXPIRE", order.getOrderNo(), "订单库存占用到期", null,
                "system", "ACTIVE", "EXPIRED", null,
                "来源锁定："+order.getSourceLockNo()+"；到期："+order.getReservationExpiresAt()
                    +"；释放未发数量："+(order.getOrderQty()-order.getShippedQty())) != 1) {
                throw new com.bike.server.common.BusinessException("ORDER_AUDIT_FAILED", "订单占用到期审计保存失败");
            }
        }
    }
}
