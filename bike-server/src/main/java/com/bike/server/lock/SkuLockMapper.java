package com.bike.server.lock;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SkuLockMapper {
    record RequestRow(long id,String payloadHash) {}
    LocalDateTime now();
    RequestRow request(@Param("operatorId") long operatorId,@Param("requestId") String requestId);
    int insertRequest(@Param("operatorId") long operatorId,@Param("requestId") String requestId,@Param("payloadHash") String payloadHash);
    List<InventoryLockView> locks(@Param("requestId") long requestId);
    int insertLock(@Param("lockNo") String lockNo,@Param("request") SkuLockRequest request,
            @Param("skuId") long skuId,@Param("modelId") long modelId,@Param("quantity") int quantity,
            @Param("expiresAt") LocalDateTime expiresAt,@Param("operatorId") long operatorId,@Param("requestId") long requestId);
}
