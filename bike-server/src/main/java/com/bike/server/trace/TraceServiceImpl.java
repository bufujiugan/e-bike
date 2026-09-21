package com.bike.server.trace;

import com.bike.server.common.BusinessException;
import com.bike.server.vehicle.VehicleView;
import org.springframework.stereotype.Service;

@Service
public class TraceServiceImpl implements TraceService {
    private final TraceMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.pi.PiDispatchMapper dispatch;
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.pi.PiLogisticsMapper logistics;
    public TraceServiceImpl(TraceMapper mapper){this.mapper=mapper;}
    @Override public TraceResponse traceQr(String qrCode){com.bike.server.common.CurrentOperator.requirePermission("TRACE_READ");String frame=mapper.selectFrameByQr(normalize(qrCode));if(frame==null)throw new BusinessException("QR_NOT_FOUND","总码不存在或已失效");return traceFrame(frame);}
    @Override public TraceResponse traceFrame(String frameSn){com.bike.server.common.CurrentOperator.requirePermission("TRACE_READ");String normalized=normalize(frameSn);VehicleView vehicle=mapper.selectVehicleByFrame(normalized);if(vehicle==null)throw new BusinessException("VEHICLE_NOT_FOUND","整车不存在");return new TraceResponse(vehicle,mapper.selectBindingHistory(normalized),mapper.selectEvents(normalized),java.util.List.of(vehicle),mapper.selectStockInRecords(normalized),withDispatch(mapper.selectStockOutRecords(normalized)),mapper.selectReworkRecords(normalized));}
    @Override public TraceResponse tracePart(String partSn){com.bike.server.common.CurrentOperator.requirePermission("TRACE_READ");String normalized=normalize(partSn);String frame=mapper.selectFrameByPart(normalized);if(frame==null)throw new BusinessException("PART_NOT_FOUND","零部件暂无绑定历史");TraceResponse trace=traceFrame(frame);return new TraceResponse(trace.vehicle(),trace.bindingHistory(),trace.events(),mapper.selectVehiclesByPart(normalized),mapper.selectStockInRecordsByPart(normalized),withDispatch(mapper.selectStockOutRecordsByPart(normalized)),mapper.selectReworkRecordsByPart(normalized));}
    private java.util.List<TraceStockOutRecord> withDispatch(java.util.List<TraceStockOutRecord> records) {
        if(dispatch!=null) for(var record:records) {
            var info=dispatch.trace(record.getStockOutNo());
            if(info!=null) {record.setPiNo(info.piNo());record.setExecutionRevision(info.executionRevision());record.setDispatchBatchNo(info.batchNo());record.setDispatchSourceType(info.sourceType());}
            if(logistics!=null){var shipping=logistics.trace(record.getStockOutNo());if(shipping!=null){record.setLogisticsCarrier(shipping.carrier());record.setLogisticsTrackingNo(shipping.trackingNo());record.setLogisticsStatus(shipping.status());}}
        }
        return records;
    }
    private static String normalize(String value){if(value==null||value.isBlank())throw new BusinessException("INVALID_PARAMETER","追溯标识不能为空");return value.trim();}
}
