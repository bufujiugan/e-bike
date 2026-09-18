package com.bike.server.system;

import com.bike.server.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/system-config")
public class SystemConfigController {
    private final SystemConfigService service;
    public SystemConfigController(SystemConfigService service) { this.service = service; }

    @GetMapping public ApiResponse<List<SystemConfigView>> list() { return ApiResponse.success("查询成功", service.list()); }
    @GetMapping("/{configKey}") public ApiResponse<SystemConfigView> get(@PathVariable String configKey) { return ApiResponse.success("查询成功", service.get(configKey)); }
    @PutMapping("/{configKey}") public ApiResponse<SystemConfigView> update(@PathVariable String configKey, @Valid @RequestBody SystemConfigRequest request) { return ApiResponse.success("配置已更新", service.update(configKey, request)); }
}
