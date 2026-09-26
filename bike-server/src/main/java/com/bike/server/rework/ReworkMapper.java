package com.bike.server.rework;

import com.bike.server.assembly.AssemblyPartRecord;
import com.bike.server.vehicle.VehicleView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface ReworkMapper {
    String selectFrameByQr(@Param("qrCode") String qrCode);
    VehicleView selectVehicleForUpdate(@Param("frameSn") String frameSn);
    AssemblyPartRecord selectCurrentPartForUpdate(@Param("vehicleId") long vehicleId,@Param("partType") String partType);
    AssemblyPartRecord selectNewPartForUpdate(@Param("partSn") String partSn);
    int insertRework(@Param("reworkNo") String reworkNo,@Param("vehicleId") long vehicleId,@Param("frameSn") String frameSn,@Param("reason") String reason,@Param("operatorId") Long operatorId);
    int insertDetail(@Param("reworkNo") String reworkNo,@Param("partType") String partType,@Param("oldPartId") Long oldPartId,@Param("oldPartSn") String oldPartSn,@Param("newPartId") long newPartId,@Param("newPartSn") String newPartSn);
    int unbindOld(@Param("vehicleId") long vehicleId,@Param("partType") String partType,@Param("reason") String reason,@Param("operatorId") Long operatorId);
    int releaseOldPart(@Param("partId") long partId);
    int bindNew(@Param("vehicleId") long vehicleId,@Param("frameSn") String frameSn,@Param("partId") long partId,@Param("partSn") String partSn,@Param("partType") String partType,@Param("reason") String reason,@Param("operatorId") Long operatorId);
    int occupyNewPart(@Param("partId") long partId,@Param("vehicleId") long vehicleId,@Param("frameSn") String frameSn);
    int insertPartUnbindOperationLog(@Param("partSn") String partSn,@Param("partType") String partType,@Param("frameSn") String frameSn,@Param("operatorId") Long operatorId,@Param("operatorName") String operatorName,@Param("reason") String reason,@Param("clientIp") String clientIp);
    int insertPartBindOperationLog(@Param("partSn") String partSn,@Param("partType") String partType,@Param("frameSn") String frameSn,@Param("operatorId") Long operatorId,@Param("operatorName") String operatorName,@Param("reason") String reason,@Param("clientIp") String clientIp);
    int selectReworkIdByNo(@Param("reworkNo") String reworkNo);
    ReworkView selectById(@Param("id") long id);
    List<ReworkView> selectPage(@Param("offset") long offset,@Param("limit") long limit);
    long count();
    int updateVehicleStatus(@Param("vehicleId") long vehicleId,@Param("status") int status);
    int insertOperationLog(@Param("businessId") String businessId,@Param("frameSn") String frameSn,@Param("partType") String partType,@Param("oldPartSn") String oldPartSn,@Param("newPartSn") String newPartSn,@Param("operatorId") Long operatorId,@Param("operatorName") String operatorName,@Param("remark") String remark,@Param("clientIp") String clientIp);
}
