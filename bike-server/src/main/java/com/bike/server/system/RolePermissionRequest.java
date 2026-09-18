package com.bike.server.system;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record RolePermissionRequest(@NotNull(message="权限列表不能为空") List<Long> permissionIds) {}
