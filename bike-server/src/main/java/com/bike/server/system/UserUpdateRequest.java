package com.bike.server.system;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserUpdateRequest(@NotBlank(message="姓名不能为空") @Size(max=100,message="姓名长度不能超过 100") String displayName,
                                String userType, Long agentId, Long workstationId) { }
