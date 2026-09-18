package com.bike.server.system;

import com.bike.server.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserRoleQueryController {
    private final UserRoleQueryService service;

    public UserRoleQueryController(UserRoleQueryService service) {
        this.service = service;
    }

    @GetMapping("/{id}/roles")
    public ApiResponse<List<Long>> roleIds(@PathVariable long id) {
        return ApiResponse.success("查询成功", service.listRoleIds(id));
    }
}
