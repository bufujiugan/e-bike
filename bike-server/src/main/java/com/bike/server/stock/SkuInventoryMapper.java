package com.bike.server.stock;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SkuInventoryMapper {
    Long lockSkuModel(@Param("skuId") long skuId);
    Long vehicleSku(@Param("vehicleId") long vehicleId);
    SkuInventorySnapshot snapshot(@Param("skuId") long skuId, @Param("orderId") long orderId);
    List<SkuInventoryRow> list(@Param("modelId") Long modelId, @Param("agentId") Long agentId,
            @Param("offset") long offset, @Param("limit") long limit);
    long count(@Param("modelId") Long modelId, @Param("agentId") Long agentId);
    List<SkuReservationItem> reservationItems(@Param("orderId") long orderId);
    int markSkuScope(@Param("orderId") long orderId);
    int insertReservationItem(@Param("orderId") long orderId, @Param("skuId") long skuId, @Param("quantity") int quantity);
    int increaseShipped(@Param("orderId") long orderId, @Param("skuId") long skuId);
    int decreaseShipped(@Param("orderId") long orderId, @Param("skuId") long skuId);
    int closeRemainder(@Param("orderId") long orderId);
}
