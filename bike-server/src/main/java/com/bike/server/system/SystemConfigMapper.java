package com.bike.server.system;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SystemConfigMapper {
    List<SystemConfigView> selectAll();
    SystemConfigView selectByKey(@Param("configKey") String configKey);
    String selectValue(@Param("configKey") String configKey);
    int upsert(@Param("configKey") String configKey, @Param("request") SystemConfigRequest request, @Param("updatedBy") Long updatedBy);
    int insertOperationLog(@Param("businessId") String businessId, @Param("operatorId") Long operatorId, @Param("operatorName") String operatorName, @Param("beforeValue") String beforeValue, @Param("afterValue") String afterValue, @Param("clientIp") String clientIp);
}
