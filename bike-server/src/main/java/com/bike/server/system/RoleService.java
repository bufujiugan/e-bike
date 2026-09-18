package com.bike.server.system;
import java.util.List;
public interface RoleService { List<RoleView> list(String keyword); RoleView create(RoleCreateRequest request); RoleView update(long id, RoleCreateRequest request); RoleView updateStatus(long id,boolean enabled); List<PermissionView> permissions(long roleId); List<PermissionView> allPermissions(); List<PermissionView> updatePermissions(long roleId, RolePermissionRequest request); }
