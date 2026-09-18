package com.bike.server.vehicle;

import com.bike.server.common.ApiResponse;
import com.bike.server.common.PageResponse;
import com.bike.server.trace.TraceResponse;
import com.bike.server.trace.TraceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {
    private final VehicleService vehicleService;
    private final TraceService traceService;
    private final VehicleQrCodeService vehicleQrCodeService;
    public VehicleController(VehicleService vehicleService, TraceService traceService, VehicleQrCodeService vehicleQrCodeService) {
        this.vehicleService = vehicleService;
        this.traceService = traceService;
        this.vehicleQrCodeService = vehicleQrCodeService;
    }

    @GetMapping
    public ApiResponse<PageResponse<VehicleView>> list(@RequestParam(required = false) String keyword,
                                                        @RequestParam(required = false) Integer status,
                                                        @RequestParam(required = false) Long modelId,
                                                        @RequestParam(required = false) Long customerId,
                                                        @RequestParam(required = false) String orderNo,
                                                        @RequestParam(required = false) LocalDate fromDate,
                                                        @RequestParam(required = false) LocalDate toDate,
                                                        @RequestParam(defaultValue = "1") long page,
                                                        @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.success("查询成功", vehicleService.list(keyword, status, modelId, customerId, orderNo, fromDate, toDate, page, pageSize));
    }
    @GetMapping("/{id}") public ApiResponse<VehicleView> getById(@PathVariable long id) { return ApiResponse.success("查询成功", vehicleService.getById(id)); }
    @GetMapping("/by-frame/{frameSn}") public ApiResponse<VehicleView> getByFrame(@PathVariable String frameSn) { return ApiResponse.success("查询成功", vehicleService.getByFrame(frameSn)); }
    @GetMapping("/by-qr/{qrCode}") public ApiResponse<VehicleView> getByQr(@PathVariable String qrCode) { return ApiResponse.success("查询成功", vehicleService.getByQr(qrCode)); }
    @GetMapping("/{id}/trace") public ApiResponse<TraceResponse> trace(@PathVariable long id) { return ApiResponse.success("查询成功", traceService.traceFrame(vehicleService.getById(id).getFrameSn())); }
    @GetMapping("/{id}/qr") public ApiResponse<java.util.Map<String, Object>> qr(@PathVariable long id) { VehicleView vehicle = vehicleService.getById(id); java.util.Map<String, Object> result = new java.util.LinkedHashMap<>(); result.put("vehicleId", vehicle.getId()); result.put("frameSn", vehicle.getFrameSn()); result.put("qrCode", vehicle.getVehicleQrCode()); result.put("qrToken", vehicle.getVehicleQrToken()); result.put("qrVersion", vehicle.getQrVersion()); result.put("generatedAt", vehicle.getQrGeneratedAt()); return ApiResponse.success("查询成功", result); }
    @GetMapping("/{id}/qr/image") public ResponseEntity<ByteArrayResource> qrImage(@PathVariable long id, @RequestParam(defaultValue = "320") int size) {
        byte[] content = vehicleQrCodeService.renderPng(id, size);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"vehicle-" + id + "-qr.png\"")
                .body(new ByteArrayResource(content));
    }
    @PostMapping public ApiResponse<VehicleView> create(@Valid @RequestBody VehicleCreateRequest request) { return ApiResponse.success("创建成功", vehicleService.create(request)); }
    @PostMapping("/{id}/qr/regenerate") public ApiResponse<VehicleView> regenerateQr(@PathVariable long id) { return ApiResponse.success("二维码已重新生成", vehicleService.regenerateQr(id)); }
}
