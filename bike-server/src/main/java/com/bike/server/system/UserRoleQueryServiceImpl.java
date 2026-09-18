package com.bike.server.system;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserRoleQueryServiceImpl implements UserRoleQueryService {
    private final UserMapper userMapper;

    public UserRoleQueryServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public List<Long> listRoleIds(long userId) {
        CurrentOperator.requireRole("SUPER_ADMIN");
        if (userId <= 0 || userMapper.selectById(userId) == null) {
            throw new BusinessException("USER_NOT_FOUND", "用户不存在");
        }
        return userMapper.selectRoleIds(userId);
    }
}
