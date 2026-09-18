package com.bike.server.system;

import java.util.List;

public interface UserRoleQueryService {
    List<Long> listRoleIds(long userId);
}
