package com.bike.server.trace;

import com.bike.server.vehicle.VehicleView;
import java.util.List;

public record TraceResponse(VehicleView vehicle, List<TraceEvent> bindingHistory, List<TraceEvent> events,
                            List<VehicleView> relatedVehicles, List<TraceStockInRecord> stockInRecords,
                            List<TraceStockOutRecord> stockOutRecords, List<TraceReworkRecord> reworkRecords) {
    public TraceResponse(VehicleView vehicle, List<TraceEvent> bindingHistory, List<TraceEvent> events) {
        this(vehicle, bindingHistory, events, List.of(vehicle), List.of(), List.of(), List.of());
    }

    public TraceResponse(VehicleView vehicle, List<TraceEvent> bindingHistory, List<TraceEvent> events,
                         List<VehicleView> relatedVehicles) {
        this(vehicle, bindingHistory, events, relatedVehicles, List.of(), List.of(), List.of());
    }
}
