package com.bike.server.customer;

import com.bike.server.customer.CustomerStatusService.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface CustomerStatusMapper {
    List<OrderSummary> selectOrders(@Param("customerId") Long customerId, @Param("agentId") Long agentId,
                                    @Param("kind") String kind, @Param("id") Long id, @Param("offset") long offset);
    long countOrders(@Param("customerId") Long customerId, @Param("agentId") Long agentId);
    List<Logistics> selectOrderLogistics(@Param("orderId") long orderId);
    List<Logistics> selectSeriesLogistics(@Param("seriesId") long seriesId);
    List<Inventory> selectInventory(@Param("agentId") Long agentId, @Param("offset") long offset);
    long countInventory(@Param("agentId") Long agentId);
}
