package com.bike.server.agent;

import com.bike.server.common.ApiResponse;
import com.bike.server.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent/purchase-intents")
public class PurchaseIntentController {
    private final PurchaseIntentService service;

    public PurchaseIntentController(PurchaseIntentService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<PurchaseIntentView>> list(@RequestParam(defaultValue = "1") long page,
                                                               @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.success("查询成功", service.list(page, pageSize));
    }

    @PostMapping
    public ApiResponse<PurchaseIntentView> create(@Valid @RequestBody PurchaseIntentCreateRequest request) {
        return ApiResponse.success("采购意向已提交", service.create(request));
    }
}
