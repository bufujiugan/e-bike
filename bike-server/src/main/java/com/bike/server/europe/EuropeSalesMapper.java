package com.bike.server.europe;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EuropeSalesMapper {
    record StoredOrder(long id, String orderNo, Long customerId, Long warehouseId, String customerName,
            String warehouseName, String currencyCode, String detailsJson, String stepsJson, int revision,
            LocalDateTime createdAt, LocalDateTime updatedAt) {}
    List<StoredOrder> selectOrders(@Param("keyword") String keyword,@Param("warehouseId") Long warehouseId,
            @Param("businessType") String businessType,@Param("offset") long offset,@Param("limit") long limit);
    long countOrders(@Param("keyword") String keyword,@Param("warehouseId") Long warehouseId,@Param("businessType") String businessType);
    StoredOrder selectOrder(@Param("id") long id);
    StoredOrder selectOrderForUpdate(@Param("id") long id);
    StoredOrder selectByRequestId(@Param("requestId") String requestId);
    int insertOrder(@Param("orderNo") String orderNo,@Param("r") FactorySalesRequest r,@Param("details") String details,@Param("operatorId") Long operatorId);
    int updateOrder(@Param("id") long id,@Param("r") FactorySalesRequest r,@Param("details") String details);
    int updateSteps(@Param("id") long id,@Param("revision") int revision,@Param("steps") String steps);
    List<FactorySalesView.LinkedOrder> selectLinkedOrders(@Param("id") long id);
    Long selectFactoryOrderForSalesOrder(@Param("orderId") long orderId);
    int insertOrderLink(@Param("id") long id,@Param("orderId") long orderId);
    int deleteOrderLink(@Param("id") long id,@Param("orderId") long orderId);
    List<SalesSettlementView> selectSettlements(@Param("type") String type,@Param("subjectId") long subjectId);
    SalesSettlementView selectSettlementRequest(@Param("type") String type,@Param("subjectId") long subjectId,@Param("requestId") String requestId);
    int insertSettlement(@Param("type") String type,@Param("subjectId") long subjectId,@Param("r") SalesSettlementRequest r,@Param("operatorId") Long operatorId);
    int updateSettlementSupplement(@Param("type") String type,@Param("subjectId") long subjectId,@Param("id") long id,@Param("r") SalesSettlementSupplement r);
    int reverseSettlement(@Param("type") String type,@Param("subjectId") long subjectId,@Param("id") long id,@Param("reason") String reason,@Param("operatorId") Long operatorId);
}
