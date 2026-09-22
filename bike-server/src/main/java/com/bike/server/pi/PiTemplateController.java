package com.bike.server.pi;

import com.bike.server.common.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pi-templates")
public class PiTemplateController {
    private final PiTemplateService service;
    public PiTemplateController(PiTemplateService service){this.service=service;}
    @GetMapping public ApiResponse<PageResponse<PiTemplate>> list(@RequestParam(required=false) String tradeType,
            @RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long pageSize) {
        return ApiResponse.success("查询成功",service.list(tradeType,page,pageSize));
    }
    @PostMapping public ApiResponse<PiTemplate> publish(@Valid @RequestBody PiTemplateRequest request) {
        return ApiResponse.success("模板版本已发布",service.publish(request));
    }
}
