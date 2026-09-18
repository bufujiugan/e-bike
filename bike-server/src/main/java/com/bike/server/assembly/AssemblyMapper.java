package com.bike.server.assembly;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AssemblyMapper {
    AssemblyPartRecord selectPartForUpdate(@Param("partSn") String partSn);
    int insertBinding(@Param("vehicleId") long vehicleId, @Param("frameSn") String frameSn, @Param("partId") long partId,
                      @Param("partSn") String partSn, @Param("partType") String partType, @Param("operatorId") Long operatorId, @Param("operatorName") String operatorName);
    int markPartBound(@Param("partId") long partId, @Param("vehicleId") long vehicleId, @Param("frameSn") String frameSn);
    int insertPartBindingLog(@Param("partSn") String partSn, @Param("frameSn") String frameSn,
                             @Param("operatorId") Long operatorId, @Param("operatorName") String operatorName,
                             @Param("clientIp") String clientIp);
    int insertOperationLog(@Param("businessId") String businessId, @Param("action") String action,
                           @Param("frameSn") String frameSn, @Param("operatorId") Long operatorId, @Param("operatorName") String operatorName, @Param("remark") String remark, @Param("clientIp") String clientIp);
}
