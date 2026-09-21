package com.bike.server.pi;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface PiShipmentMapper {
    List<PiShipmentGate> selectForOrder(@Param("orderId") long orderId);
    Integer remainingForVehicle(@Param("piId") long piId, @Param("orderId") long orderId,
                                @Param("vehicleId") long vehicleId);
    int countForOrder(@Param("orderId") long orderId);
}
