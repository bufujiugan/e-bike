package com.bike.server.system;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record UserRoleRequest(@NotNull(message="角色列表不能为空") List<Long> roleIds) { }
