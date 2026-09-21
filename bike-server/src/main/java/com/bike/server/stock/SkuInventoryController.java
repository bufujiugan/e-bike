package com.bike.server.stock;

import com.bike.server.common.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class SkuInventoryController {
    private final SkuInventoryService service;
    public SkuInventoryController(SkuInventoryService service) { this.service=service; }
    @GetMapping("/api/inventory/skus")
    public ApiResponse<PageResponse<SkuInventoryRow>> list(@RequestParam(required=false) Long modelId,
            @RequestParam(defaultValue="1") long page, @RequestParam(defaultValue="20") long pageSize) {
        return ApiResponse.success("查询成功",service.list(modelId,page,pageSize));
    }
}
