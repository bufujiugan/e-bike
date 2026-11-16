package com.bike.server.europe;

import com.bike.server.common.BusinessException;
import com.bike.server.pi.PiBusinessMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 只对已关联工厂直签的订单生效；沿用库存事务互斥，不影响普通订单。 */
@Component
public class FactorySalesShipmentGuard {
    private final EuropeSalesMapper mapper;
    private final PiBusinessMapper warehouses;
    private final ObjectMapper json;
    public FactorySalesShipmentGuard(EuropeSalesMapper mapper,PiBusinessMapper warehouses,ObjectMapper json) {
        this.mapper=mapper;this.warehouses=warehouses;this.json=json;
    }
    public void requireCollection(long orderId,Long customerId) {
        Long factoryId=mapper.selectFactoryOrderForSalesOrder(orderId);
        if(factoryId==null) return;
        var order=mapper.selectOrder(factoryId);
        if(order==null || !Objects.equals(order.customerId(),customerId)) throw new BusinessException("FACTORY_CUSTOMER_MISMATCH","工厂合同与出库订单客户不一致");
        try {
            if(!json.readTree(order.stepsJson()).hasNonNull("COLLECTION"))
                throw new BusinessException("FACTORY_COLLECTION_CONFIRM_REQUIRED","工厂直签先付款后提货，请经办人先确认工厂已收款；凭证可后补");
        } catch(JsonProcessingException error) { throw new IllegalStateException("工厂收款确认读取失败",error); }
    }
    public void requireWarehouse(long orderId,long vehicleId) {
        Long factoryId=mapper.selectFactoryOrderForSalesOrder(orderId);
        if(factoryId==null) return;
        var order=mapper.selectOrder(factoryId);
        if(order.warehouseId()!=null && !Objects.equals(order.warehouseId(),warehouses.selectVehicleWarehouse(vehicleId)))
            throw new BusinessException("FACTORY_WAREHOUSE_MISMATCH","实际车辆不属于工厂直签订单所选仓库");
    }
}
