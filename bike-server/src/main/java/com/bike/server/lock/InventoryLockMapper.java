package com.bike.server.lock;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface InventoryLockMapper {
    int lockModelForUpdate(@Param("modelId") long modelId);
    long selectPhysicalForUpdate(@Param("modelId") long modelId);
    long selectActiveLockedForUpdate(@Param("modelId") long modelId);
    int insert(@Param("lockNo") String lockNo,@Param("request") InventoryLockCreateRequest request,@Param("expireTime") java.time.LocalDateTime expireTime,@Param("createdBy") Long createdBy);
    int insertDetail(@Param("lockNo") String lockNo, @Param("productModelId") long productModelId, @Param("lockQty") int lockQty);
    InventoryLockView selectById(@Param("id") long id);
    long selectIdByNo(@Param("lockNo") String lockNo);
    List<InventoryLockView> selectPage(@Param("agentId") Long agentId,@Param("offset") long offset,@Param("limit") long limit);
    long count(@Param("agentId") Long agentId);
    int release(@Param("id") long id,@Param("reason") String reason);
    int convert(@Param("id") long id,@Param("qty") int qty);
    int expireLocks();
    List<InventoryLockView> selectExpiredForAudit();
    int insertOperationLog(@Param("businessId") String businessId, @Param("action") String action, @Param("operatorId") Long operatorId, @Param("operatorName") String operatorName, @Param("remark") String remark, @Param("clientIp") String clientIp);
}
