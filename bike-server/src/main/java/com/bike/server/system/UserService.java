package com.bike.server.system;
import com.bike.server.common.PageResponse;
public interface UserService { PageResponse<UserView> list(String keyword,long page,long pageSize); UserView create(UserCreateRequest request); UserView update(long id, UserUpdateRequest request); UserView updateStatus(long id,boolean enabled); UserView updateRoles(long id, UserRoleRequest request); }
