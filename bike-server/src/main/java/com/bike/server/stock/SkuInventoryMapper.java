package com.bike.server.stock;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SkuInventoryMapper {
    Long lockSkuModel(@Param("skuId") long skuId);
    Long vehicleSku(@Param("vehicleId") long vehicleId);
    String selectReservationHoldMode(@Param("orderId") long orderId);
    SkuInventorySnapshot snapshot(@Param("skuId") long skuId, @Param("orderId") long orderId);
    SkuInventorySnapshot selectFulfillmentSnapshot(@Param("skuId") long skuId, @Param("orderId") long orderId);
    List<SkuInventoryRow> list(@Param("modelId") Long modelId, @Param("agentId") Long agentId,
            @Param("offset") long offset, @Param("limit") long limit);
    long count(@Param("modelId") Long modelId, @Param("agentId") Long agentId);
    List<SkuReservationItem> reservationItems(@Param("orderId") long orderId);
    int markSkuScope(@Param("orderId") long orderId);
    int insertReservationItem(@Param("orderId") long orderId, @Param("skuId") long skuId, @Param("quantity") int quantity);
    int insertDomesticReservationItem(@Param("orderId") long orderId, @Param("skuId") long skuId, @Param("quantity") int quantity);
    int increaseShipped(@Param("orderId") long orderId, @Param("skuId") long skuId);
    int decreaseShipped(@Param("orderId") long orderId, @Param("skuId") long skuId);
    int countLockSources(@Param("orderId") long orderId, @Param("skuId") long skuId);
    Long selectShipmentSource(@Param("orderId") long orderId, @Param("skuId") long skuId);
    int increaseSourceShipped(@Param("orderId") long orderId, @Param("lockId") long lockId);
    int insertShipmentSource(@Param("stockNo") String stockNo, @Param("orderId") long orderId, @Param("lockId") long lockId);
    Long selectStockOutSource(@Param("stockOutId") long stockOutId);
    int decreaseSourceShipped(@Param("orderId") long orderId, @Param("lockId") long lockId);
    int closeRemainder(@Param("orderId") long orderId);
}
