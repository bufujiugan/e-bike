package com.bike.server.customer;

import com.bike.server.common.ApiResponse;
import com.bike.server.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import com.bike.server.customer.CustomerPortalService.*;

@RestController
@RequestMapping("/api/customer")
public class CustomerPortalController {
    private final CustomerPortalService service;
    public CustomerPortalController(CustomerPortalService service) { this.service = service; }
    @GetMapping("/company") public ApiResponse<Company> company() { return ApiResponse.success(service.getCompany()); }
    @GetMapping("/models") public ApiResponse<List<Model>> models() { return ApiResponse.success(service.listModels()); }
    @GetMapping("/orders") public ApiResponse<PageResponse<Order>> orders(@RequestParam(defaultValue="1") long page) { return ApiResponse.success(service.listOrders(page)); }
    @PostMapping("/orders") public ApiResponse<Order> submit(@Valid @RequestBody OrderRequest request) { return ApiResponse.success("订单已提交，等待业务确认", service.submitOrder(request)); }
}

