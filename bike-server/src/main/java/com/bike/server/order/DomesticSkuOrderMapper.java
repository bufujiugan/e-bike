package com.bike.server.order;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DomesticSkuOrderMapper {
    record ExistingOrder(long orderId, String payloadHash) {}
    record ExpiredSource(long orderId, long lockId, String orderNo, String lockNo, String skuCode,
            java.time.LocalDateTime expiresAt, int remainingQty) {}
    ExistingOrder findRequest(@Param("operatorId") long operatorId, @Param("requestId") String requestId);
    int insertRequest(@Param("orderId") long orderId, @Param("operatorId") long operatorId,
            @Param("requestId") String requestId, @Param("payloadHash") String payloadHash);
    int insertSource(@Param("orderId") long orderId, @Param("source") DomesticOrderLockSource source);
    List<DomesticOrderLine> selectLines(@Param("orderId") long orderId);
    List<DomesticOrderLockSource> selectSources(@Param("orderId") long orderId);
    List<DomesticSkuOption> selectSkuOptions(@Param("keyword") String keyword);
    int renewSources(@Param("orderId") long orderId);
    List<ExpiredSource> selectExpiredSources();
    int markSourceExpiryAudited(@Param("orderId") long orderId, @Param("lockId") long lockId);
}
