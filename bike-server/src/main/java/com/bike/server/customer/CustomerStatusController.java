package com.bike.server.customer;

import com.bike.server.common.ApiResponse;
import com.bike.server.common.BusinessException;
import com.bike.server.common.PageResponse;
import com.bike.server.customer.CustomerStatusService.*;
import com.bike.server.part.PartPricingService;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customer")
public class CustomerStatusController {
    private final CustomerStatusService service;

    public CustomerStatusController(CustomerStatusService service) {
        this.service = service;
    }

    @GetMapping("/order-status")
    public ApiResponse<PageResponse<OrderSummary>> orders(@RequestParam(defaultValue = "1") long page) {
        return ApiResponse.success(service.listOrders(page));
    }

    @GetMapping("/order-detail")
    public ApiResponse<OrderDetail> order(@RequestParam String kind, @RequestParam @Positive long id) {
        return ApiResponse.success(service.getOrder(kind, id));
    }

    @DeleteMapping("/order-status")
    public ApiResponse<Void> removeOrder(@RequestParam String kind, @RequestParam long id) {
        if (id <= 0) throw new BusinessException("INVALID_PARAMETER", "请求参数校验失败");
        service.removeCancelledOrder(kind, id);
        return ApiResponse.success(null);
    }

    @GetMapping("/inventory-status")
    public ApiResponse<PageResponse<Inventory>> inventory(@RequestParam(defaultValue = "1") long page) {
        return ApiResponse.success(service.listInventory(page));
    }

    @GetMapping("/part-prices")
    public ApiResponse<PageResponse<PartPricingService.Price>> parts(@RequestParam(required = false) @Size(max = 128) String keyword,
                                                                     @RequestParam(defaultValue = "1") long page) {
        return ApiResponse.success(service.listParts(keyword, page));
    }
}
