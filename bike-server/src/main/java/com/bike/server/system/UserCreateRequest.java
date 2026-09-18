package com.bike.server.system;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserCreateRequest(@NotBlank(message="用户名不能为空") @Size(max=64,message="用户名长度不能超过 64") String username,
                                @NotBlank(message="密码不能为空") @Size(min=8,max=100,message="密码长度必须为 8-100") String password,
                                @NotBlank(message="姓名不能为空") @Size(max=100,message="姓名长度不能超过 100") String displayName,
                                String userType, Long agentId, Long workstationId) {
    public UserCreateRequest(String username, String password, String displayName, String userType) {
        this(username, password, displayName, userType, null, null);
    }
}
