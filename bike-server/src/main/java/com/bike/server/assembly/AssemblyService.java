package com.bike.server.assembly;

import com.bike.server.vehicle.VehicleView;

public interface AssemblyService {
    AssemblyValidationResult validateFrame(String frameSn);
    AssemblyValidationResult validatePart(AssemblyScanRequest request);
    VehicleView submit(AssemblySubmitRequest request);
    VehicleView submitCollected(String taskId, AssemblySubmitRequest request);
    VehicleView getByFrame(String frameSn);
}
