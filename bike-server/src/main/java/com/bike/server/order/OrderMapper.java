package com.bike.server.order;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OrderMapper {
    List<SalesOrderView> selectPage(@Param("keyword") String keyword, @Param("status") String status, @Param("agentId") Long agentId, @Param("offset") long offset, @Param("limit") long limit);
    long count(@Param("keyword") String keyword, @Param("status") String status, @Param("agentId") Long agentId);
    SalesOrderView selectById(@Param("id") long id);
    SalesOrderView selectByNo(@Param("orderNo") String orderNo);
    List<OrderVehicleView> selectShippedVehicles(@Param("orderId") long orderId);
    int insertOrder(@Param("orderNo") String orderNo, @Param("request") SalesOrderCreateRequest request, @Param("createdBy") Long createdBy);
    int insertItem(@Param("orderNo") String orderNo, @Param("request") SalesOrderCreateRequest request);
    int updateOrder(@Param("id") long id, @Param("request") SalesOrderCreateRequest request);
    int updateItem(@Param("orderId") long orderId, @Param("request") SalesOrderCreateRequest request);
    int updateStatus(@Param("id") long id, @Param("status") String status);
    int increaseShipped(@Param("orderId") long orderId, @Param("qty") int qty);
    int decreaseShipped(@Param("orderId") long orderId, @Param("qty") int qty);
    SalesOrderView selectForUpdate(@Param("id") long id);
    int countEnabledCustomer(@Param("customerId") long customerId);
    int countEnabledModel(@Param("modelId") long modelId);
    int countEnabledAgent(@Param("agentId") long agentId);
    int insertOperationLog(@Param("businessType") String businessType, @Param("businessId") String businessId, @Param("action") String action, @Param("operatorId") Long operatorId, @Param("operatorName") String operatorName, @Param("beforeStatus") String beforeStatus, @Param("afterStatus") String afterStatus, @Param("clientIp") String clientIp, @Param("remark") String remark);
}
