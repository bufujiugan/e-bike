package com.bike.server.system;

import com.bike.server.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/{id}/customer-scope")
public class CustomerScopeController {
    private final CustomerScopeService service;
    public CustomerScopeController(CustomerScopeService service){this.service=service;}
    @GetMapping public ApiResponse<CustomerScopeService.View> get(@PathVariable long id){return ApiResponse.success(service.get(id));}
    @PutMapping public ApiResponse<CustomerScopeService.View> save(@PathVariable long id,@Valid @RequestBody CustomerScopeService.Request request){return ApiResponse.success(service.save(id,request));}
}
