package com.bike.server.system;

import com.bike.server.common.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customer-scopes")
public class CustomerScopeOptionsController {
    private final CustomerScopeService service;
    public CustomerScopeOptionsController(CustomerScopeService service){this.service=service;}
    @GetMapping("/users") public ApiResponse<PageResponse<CustomerScopeMapper.UserOption>> users(
            @RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long pageSize) {
        return ApiResponse.success(service.users(keyword,page,pageSize));
    }
    @GetMapping("/customers") public ApiResponse<PageResponse<CustomerScopeMapper.CustomerOption>> customers(
            @RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="50") long pageSize) {
        return ApiResponse.success(service.customers(keyword,page,pageSize));
    }
}
