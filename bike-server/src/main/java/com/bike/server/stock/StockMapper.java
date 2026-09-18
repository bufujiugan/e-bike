package com.bike.server.stock;

import com.bike.server.vehicle.VehicleView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StockMapper {
    VehicleView selectVehicleByQrForUpdate(@Param("qrCode") String qrCode);
    int updateVehicleStockIn(@Param("vehicleId") long vehicleId, @Param("warehouseId") long warehouseId, @Param("locationId") long locationId);
    int updateVehicleStockOut(@Param("vehicleId") long vehicleId, @Param("customerId") long customerId, @Param("orderId") long orderId);
    int updateVehicleStockInCancel(@Param("vehicleId") long vehicleId);
    int updateVehicleStockOutCancel(@Param("vehicleId") long vehicleId, @Param("customerId") long customerId, @Param("orderNo") String orderNo, @Param("stockOutRecordId") long stockOutRecordId);
    int countEnabledLocation(@Param("warehouseId") long warehouseId, @Param("locationId") long locationId);
    int countAuthorizedModel(@Param("agentId") long agentId, @Param("modelId") long modelId);
    int updateOrderStatusAfterCancel(@Param("orderId") long orderId, @Param("status") String status);
    int insertStockIn(@Param("stockNo") String stockNo, @Param("vehicleId") long vehicleId, @Param("warehouseId") long warehouseId, @Param("locationId") long locationId, @Param("operatorId") Long operatorId);
    int insertStockOut(@Param("stockNo") String stockNo, @Param("vehicleId") long vehicleId, @Param("customerId") long customerId, @Param("orderId") long orderId, @Param("operatorId") Long operatorId);
    int insertOrderVehicle(@Param("orderId") long orderId, @Param("vehicleId") long vehicleId, @Param("stockNo") String stockNo);
    StockRecordView selectStockIn(@Param("id") long id);
    StockRecordView selectStockInByNo(@Param("stockNo") String stockNo);
    StockRecordView selectStockOut(@Param("id") long id);
    StockRecordView selectStockOutByNo(@Param("stockNo") String stockNo);
    int cancelStockIn(@Param("id") long id, @Param("reason") String reason, @Param("operatorId") Long operatorId);
    int cancelStockOut(@Param("id") long id, @Param("reason") String reason, @Param("operatorId") Long operatorId);
    List<StockRecordView> selectStockRecords(@Param("type") String type, @Param("offset") long offset, @Param("limit") long limit);
    long countStockRecords(@Param("type") String type);
    List<InventoryRow> selectInventory(@Param("modelId") Long modelId, @Param("warehouseId") Long warehouseId, @Param("agentId") Long agentId);
    long countPhysicalInventory();
    long countLockedInventory();
    int insertOperationLog(@Param("businessType") String businessType, @Param("businessId") String businessId, @Param("action") String action, @Param("frameSn") String frameSn, @Param("operatorId") Long operatorId, @Param("operatorName") String operatorName, @Param("remark") String remark, @Param("clientIp") String clientIp);
}
