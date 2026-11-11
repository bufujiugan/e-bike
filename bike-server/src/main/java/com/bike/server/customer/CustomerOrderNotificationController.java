package com.bike.server.customer;

import com.bike.server.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import com.bike.server.customer.CustomerPortalService.Order;

@RestController
@RequestMapping("/api/customer-order-notifications")
public class CustomerOrderNotificationController {
    private final CustomerPortalService service;
    public CustomerOrderNotificationController(CustomerPortalService service) { this.service = service; }
    @GetMapping public ApiResponse<List<Order>> list() { return ApiResponse.success(service.listUnreadOrders()); }
    @PostMapping("/{id}/read") public ApiResponse<Void> acknowledge(@PathVariable long id) { service.acknowledgeOrder(id); return ApiResponse.success("已读", null); }
}

