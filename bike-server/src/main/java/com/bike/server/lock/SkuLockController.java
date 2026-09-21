package com.bike.server.lock;

import com.bike.server.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
public class SkuLockController {
    private final SkuLockService service;
    public SkuLockController(SkuLockService service) { this.service=service; }
    @PostMapping("/api/sku-locks")
    public ApiResponse<List<InventoryLockView>> create(@Valid @RequestBody SkuLockRequest request) {
        return ApiResponse.success("SKU库存锁定成功",service.create(request));
    }
}
