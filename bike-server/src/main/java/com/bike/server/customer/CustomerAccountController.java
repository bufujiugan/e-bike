package com.bike.server.customer;

import com.bike.server.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.bike.server.customer.CustomerPortalService.*;

@RestController
@RequestMapping("/api/customer-accounts")
public class CustomerAccountController {
    private final CustomerPortalService service;
    public CustomerAccountController(CustomerPortalService service) { this.service = service; }
    @PostMapping public ApiResponse<Account> create(@Valid @RequestBody AccountRequest request) { return ApiResponse.success("客户账号已创建，使用统一入口登录", service.createAccount(request)); }
}

