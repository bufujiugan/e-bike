package com.bike.server.europe;

import com.bike.server.common.BusinessException;
import com.bike.server.pi.PiBusinessMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FactorySalesShipmentGuardTest {
    private final EuropeSalesMapper mapper=mock(EuropeSalesMapper.class);
    private final PiBusinessMapper warehouses=mock(PiBusinessMapper.class);
    private final FactorySalesShipmentGuard guard=new FactorySalesShipmentGuard(mapper,warehouses,new ObjectMapper());
    private void linked(String steps) {
        when(mapper.selectFactoryOrderForSalesOrder(7)).thenReturn(1L);
        when(mapper.selectOrder(1)).thenReturn(new EuropeSalesMapper.StoredOrder(1,"FS1",10L,2L,"客户","仓库","EUR","{}",steps,1,null,null));
    }
    @Test void ordinaryOrdersKeepExistingBehavior() {
        when(mapper.selectFactoryOrderForSalesOrder(7)).thenReturn(null);
        assertThatCode(()->{guard.requireCollection(7,10L);guard.requireWarehouse(7,8);}).doesNotThrowAnyException();
        verifyNoInteractions(warehouses);
    }
    @Test void factoryOrdersRequireManualCollectionWithoutRequiringAttachmentsOrLedger() {
        linked("{}");assertThatThrownBy(()->guard.requireCollection(7,10L)).isInstanceOf(BusinessException.class);
        linked("{\"COLLECTION\":{\"operatorName\":\"经办人\"}}");
        assertThatCode(()->guard.requireCollection(7,10L)).doesNotThrowAnyException();
        assertThatThrownBy(()->guard.requireCollection(7,11L)).isInstanceOf(BusinessException.class);
    }
    @Test void physicalWarehouseIsStillChecked() {
        linked("{}");when(warehouses.selectVehicleWarehouse(8)).thenReturn(3L);
        assertThatThrownBy(()->guard.requireWarehouse(7,8)).isInstanceOf(BusinessException.class);
        when(warehouses.selectVehicleWarehouse(8)).thenReturn(2L);
        assertThatCode(()->guard.requireWarehouse(7,8)).doesNotThrowAnyException();
    }
}
