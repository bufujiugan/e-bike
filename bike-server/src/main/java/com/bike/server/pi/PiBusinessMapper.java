package com.bike.server.pi;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PiBusinessMapper {
    List<Map<String,Object>> selectWarehouses();
    int countEnabledWarehouse(@Param("id") long id);
    String selectDocument(@Param("piId") long piId);
    Long selectVehicleWarehouse(@Param("vehicleId") long vehicleId);
    Long selectVehicleSku(@Param("vehicleId") long vehicleId);
    int countWarehouseStock(@Param("warehouseId") long warehouseId,@Param("skuId") long skuId);
    int countWarehouseReserved(@Param("warehouseId") long warehouseId,@Param("skuId") long skuId,@Param("excludedSeriesId") long excludedSeriesId);
    int insertPaidSource(@Param("piId") long piId,@Param("skuId") long skuId,@Param("quantity") int quantity,@Param("lockNo") String lockNo,@Param("operatorId") Long operatorId);
    Long selectLockId(@Param("lockNo") String lockNo);
    int insertPaidReservation(@Param("orderId") long orderId,@Param("lockId") long lockId,@Param("piId") long piId,@Param("operatorId") Long operatorId);
    int updatePaidReservation(@Param("orderId") long orderId,@Param("piId") long piId,@Param("operatorId") Long operatorId);
    int countPaymentsWithoutReceipt(@Param("piId") long piId);
    int countUnshipped(@Param("piId") long piId);
    int countActiveShipments(@Param("piId") long piId);
    int countUntrackedBatches(@Param("piId") long piId);
    int countArrivalProof(@Param("piId") long piId,@Param("attachmentId") String attachmentId);
    int updateDeliveryPending(@Param("piId") long piId);
}
