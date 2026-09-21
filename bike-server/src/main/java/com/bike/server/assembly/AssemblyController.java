package com.bike.server.assembly;

import com.bike.server.common.ApiResponse;
import com.bike.server.vehicle.VehicleView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequestMapping("/api/assembly")
public class AssemblyController {
    private final AssemblyService assemblyService;
    private final com.bike.server.production.LegacyAssemblyService legacyAssemblyService;
    public AssemblyController(AssemblyService assemblyService, com.bike.server.production.LegacyAssemblyService legacyAssemblyService) { this.assemblyService = assemblyService; this.legacyAssemblyService=legacyAssemblyService; }

    @PostMapping("/validate-frame")
    public ApiResponse<AssemblyValidationResult> validateFrame(@Valid @RequestBody FrameScanRequest request) {
        return ApiResponse.success("校验完成", assemblyService.validateFrame(request.frameSn()));
    }
    @PostMapping("/validate-part")
    public ApiResponse<AssemblyValidationResult> validatePart(@Valid @RequestBody AssemblyScanRequest request) {
        return ApiResponse.success("校验完成", assemblyService.validatePart(request));
    }
    @PostMapping("/submit")
    public ApiResponse<VehicleView> submit(@Valid @RequestBody AssemblySubmitRequest request) {
        return ApiResponse.success("组装完成", legacyAssemblyService.submit(request));
    }

    @GetMapping("/{frameSn}")
    public ApiResponse<VehicleView> getByFrame(@PathVariable String frameSn) {
        return ApiResponse.success("查询成功", assemblyService.getByFrame(frameSn));
    }
}
