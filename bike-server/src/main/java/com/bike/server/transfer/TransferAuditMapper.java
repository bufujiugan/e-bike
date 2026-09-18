package com.bike.server.transfer;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TransferAuditMapper {
    int insert(@Param("businessType") String businessType, @Param("businessId") String businessId, @Param("operatorId") Long operatorId, @Param("operatorName") String operatorName, @Param("clientIp") String clientIp, @Param("beforeData") String beforeData, @Param("afterData") String afterData);
}
