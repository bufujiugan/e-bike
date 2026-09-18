package com.bike.server.common;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OperationLogMapper {
    int insert(@Param("businessType") String businessType,
               @Param("businessId") String businessId,
               @Param("action") String action,
               @Param("operatorId") Long operatorId,
               @Param("operatorName") String operatorName,
               @Param("beforeData") String beforeData,
               @Param("afterData") String afterData,
               @Param("clientIp") String clientIp,
               @Param("remark") String remark);
}
