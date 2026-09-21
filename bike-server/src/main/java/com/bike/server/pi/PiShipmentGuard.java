package com.bike.server.pi;

import com.bike.server.common.BusinessException;
import com.bike.server.order.SalesOrderView;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** Called after the sales-order row is locked, in the same transaction as physical dispatch. */
@Component
public class PiShipmentGuard {
    private final PiShipmentMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired private PiSeriesMapper series;
    public PiShipmentGuard(PiShipmentMapper mapper) { this.mapper = mapper; }

    public PiShipmentGate requireReleased(SalesOrderView order) {
        var linked = series == null ? mapper.selectForOrder(order.getId()) : series.gates(order.getId());
        if (linked.isEmpty()) return null;
        if (linked.size() != 1) throw new BusinessException("PI_ORDER_CONFLICT", "订单关联多个 PI，须先核对后出库");
        PiShipmentGate pi = linked.getFirst();
        if (!Objects.equals(pi.customerId(), order.getCustomerId())) {
            throw new BusinessException("PI_CUSTOMER_MISMATCH", "PI 与出库订单客户不一致");
        }
        if (!"RELEASED".equals(pi.status()) || !"RELEASED".equals(pi.releaseStatus())
                || pi.shipmentRequiredAmount() == null || pi.paidAmount() == null
                || pi.paidAmount().compareTo(pi.shipmentRequiredAmount()) < 0) {
            throw new BusinessException("PI_SHIPMENT_NOT_RELEASED", "关联 PI 尚未达到发货收款条件并完成授权放行");
        }
        return pi;
    }

    public void requireVehicle(PiShipmentGate pi, long orderId, long vehicleId) {
        if (pi == null) return;
        Integer remaining = series == null ? mapper.remainingForVehicle(pi.id(), orderId, vehicleId)
                : series.remainingForVehicle(pi.id(), vehicleId);
        if (remaining == null) throw new BusinessException("PI_SKU_MISMATCH", "整车 SKU 不在 PI 明细中，不能按同车型替代");
        if (remaining <= 0) throw new BusinessException("PI_SKU_QTY_EXCEEDED", "该 SKU 的 PI 数量已全部出库");
    }

    public void requireOrderEditable(long orderId) {
        if (mapper.countForOrder(orderId) > 0 || series != null && series.currentPiForOrder(orderId) != null) {
            throw new BusinessException("ORDER_LINKED_TO_PI", "订单已关联 PI，不能直接修改客户、车型或数量");
        }
    }
}
