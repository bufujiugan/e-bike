package com.bike.server.pi;

import com.bike.server.common.BusinessException;
import com.bike.server.order.SalesOrderView;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PiShipmentGuardTest {
    private final PiShipmentMapper mapper = mock(PiShipmentMapper.class);
    private final PiShipmentGuard guard = new PiShipmentGuard(mapper);
    private SalesOrderView order() {
        SalesOrderView o = new SalesOrderView(); o.setId(5L); o.setCustomerId(2L); return o;
    }
    private PiShipmentGate gate(String state, String release, String required, String paid) {
        return new PiShipmentGate(1L, 2L, state, release, required == null ? null : new BigDecimal(required), new BigDecimal(paid));
    }
    @Test void fullPaymentStillRequiresExplicitRelease() {
        when(mapper.selectForOrder(5)).thenReturn(List.of(gate("CONFIRMED", "BLOCKED", "100", "100")));
        assertThatThrownBy(() -> guard.requireReleased(order())).isInstanceOf(BusinessException.class).hasMessageContaining("授权放行");
    }
    @Test void depositPaymentCannotBypassShippingThreshold() {
        when(mapper.selectForOrder(5)).thenReturn(List.of(gate("RELEASED", "RELEASED", "100", "30")));
        assertThatThrownBy(() -> guard.requireReleased(order())).isInstanceOf(BusinessException.class);
    }
    @Test void missingShippingRuleFailsClosed() {
        when(mapper.selectForOrder(5)).thenReturn(List.of(gate("RELEASED", "RELEASED", null, "100")));
        assertThatThrownBy(() -> guard.requireReleased(order())).isInstanceOf(BusinessException.class);
    }
    @Test void releasedPaidPiAllowsOnlyItsRemainingSkuQuantity() {
        var p = gate("RELEASED", "RELEASED", "100", "100");
        when(mapper.selectForOrder(5)).thenReturn(List.of(p));
        assertThat(guard.requireReleased(order())).isEqualTo(p);
        when(mapper.remainingForVehicle(1, 5, 8)).thenReturn(1);
        assertThatCode(() -> guard.requireVehicle(p, 5, 8)).doesNotThrowAnyException();
        when(mapper.remainingForVehicle(1, 5, 8)).thenReturn(0);
        assertThatThrownBy(() -> guard.requireVehicle(p, 5, 8)).isInstanceOf(BusinessException.class).hasMessageContaining("全部出库");
        when(mapper.remainingForVehicle(1, 5, 9)).thenReturn(null);
        assertThatThrownBy(() -> guard.requireVehicle(p, 5, 9)).isInstanceOf(BusinessException.class).hasMessageContaining("不能按同车型替代");
    }
    @Test void legacyOrderWithoutPiKeepsExistingWorkflow() {
        when(mapper.selectForOrder(5)).thenReturn(List.of()); assertThat(guard.requireReleased(order())).isNull();
    }
}
