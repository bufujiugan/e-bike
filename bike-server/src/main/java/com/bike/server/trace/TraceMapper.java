package com.bike.server.trace;

import com.bike.server.vehicle.VehicleView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface TraceMapper {
    String selectFrameByQr(String qrCode);
    VehicleView selectVehicleByFrame(@Param("frameSn") String frameSn);
    String selectFrameByPart(@Param("partSn") String partSn);
    List<VehicleView> selectVehiclesByPart(@Param("partSn") String partSn);
    List<TraceEvent> selectBindingHistory(@Param("frameSn") String frameSn);
    List<TraceEvent> selectEvents(@Param("frameSn") String frameSn);
    List<TraceStockInRecord> selectStockInRecords(@Param("frameSn") String frameSn);
    List<TraceStockOutRecord> selectStockOutRecords(@Param("frameSn") String frameSn);
    List<TraceReworkRecord> selectReworkRecords(@Param("frameSn") String frameSn);
    List<TraceStockInRecord> selectStockInRecordsByPart(@Param("partSn") String partSn);
    List<TraceStockOutRecord> selectStockOutRecordsByPart(@Param("partSn") String partSn);
    List<TraceReworkRecord> selectReworkRecordsByPart(@Param("partSn") String partSn);
}
