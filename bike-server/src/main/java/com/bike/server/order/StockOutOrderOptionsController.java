package com.bike.server.order;

import com.bike.server.common.ApiResponse;
import com.bike.server.common.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders/stock-out-options")
public class StockOutOrderOptionsController {
    private final StockOutOrderOptionsService service;
    public StockOutOrderOptionsController(StockOutOrderOptionsService service) { this.service = service; }

    @GetMapping
    public ApiResponse<PageResponse<StockOutOrderOption>> list(@RequestParam long customerId,
            @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.success("查询成功", service.list(customerId, keyword, page, pageSize));
    }
}
