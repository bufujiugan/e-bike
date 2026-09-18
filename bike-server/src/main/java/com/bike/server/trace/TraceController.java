package com.bike.server.trace;

import com.bike.server.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/trace")
public class TraceController {
    private final TraceService service;
    public TraceController(TraceService service){this.service=service;}
    @GetMapping("/qr/{qrCode}") public ApiResponse<TraceResponse> qr(@PathVariable String qrCode){return ApiResponse.success("查询成功",service.traceQr(qrCode));}
    @GetMapping("/frame/{frameSn}") public ApiResponse<TraceResponse> frame(@PathVariable String frameSn){return ApiResponse.success("查询成功",service.traceFrame(frameSn));}
    @GetMapping("/part/{partSn}") public ApiResponse<TraceResponse> part(@PathVariable String partSn){return ApiResponse.success("查询成功",service.tracePart(partSn));}
}
