package com.bike.server.order;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.math.BigDecimal;

@Mapper
public interface OrderReservationMapper {
    OrderReservationView selectForUpdate(@Param("orderId") long orderId);
    int holdAfterPayment(@Param("orderId") long orderId, @Param("piId") long piId,
                         @Param("paidAmount") BigDecimal paidAmount, @Param("operatorId") Long operatorId);
    int release(@Param("orderId") long orderId);
    OrderInventorySnapshot snapshot(@Param("modelId") long modelId,@Param("orderId") long orderId);
    int expiredForOrder(@Param("orderId") long orderId);
    java.util.List<SalesOrderView> expiredForAudit();
    int markExpiryAudited(@Param("orderId") long orderId);
    java.util.List<SalesOrderView> ordersForLock(@Param("lockId") long lockId,@Param("offset") long offset,@Param("limit") long limit);
    long countForLock(@Param("lockId") long lockId);
    Long findOrder(@Param("lockId") long lockId,@Param("requestId") String requestId);
    int insert(@Param("orderId") long orderId,@Param("lockId") long lockId,@Param("requestId") String requestId,
               @Param("modelId") long modelId,@Param("qty") int qty,@Param("expiresAt") LocalDateTime expiresAt);
    int countForOrder(@Param("orderId") long orderId);
    LocalDateTime expiry(@Param("orderId") long orderId);
    Long vehicleModel(@Param("vehicleId") long vehicleId);
    int renew(@Param("orderId") long orderId,@Param("hours") int hours);
}
