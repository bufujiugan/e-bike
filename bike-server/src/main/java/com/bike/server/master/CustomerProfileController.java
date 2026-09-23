package com.bike.server.master;

import com.bike.server.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/master/customers/{id}/profile")
public class CustomerProfileController {
    private final CustomerProfileService service;
    public CustomerProfileController(CustomerProfileService service){this.service=service;}
    @GetMapping public ApiResponse<CustomerProfile> get(@PathVariable long id){return ApiResponse.success("查询成功",service.get(id));}
    @PutMapping public ApiResponse<CustomerProfile> update(@PathVariable long id,@Valid @RequestBody CustomerProfileRequest request){return ApiResponse.success("保存成功",service.update(id,request));}
}
