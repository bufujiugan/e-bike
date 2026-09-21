package com.bike.server.system;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.common.PageResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class UserServiceImpl implements UserService {
    private final UserMapper mapper;
    private final PasswordEncoder encoder;

    public UserServiceImpl(UserMapper mapper, PasswordEncoder encoder) {
        this.mapper = mapper;
        this.encoder = encoder;
    }

    @Override
    public PageResponse<UserView> list(String keyword, long page, long pageSize) {
        CurrentOperator.requireRole("SUPER_ADMIN");
        long normalizedPage = Math.max(1, page);
        long normalizedSize = Math.min(100, Math.max(1, pageSize));
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return new PageResponse<>(mapper.selectPage(normalizedKeyword, (normalizedPage - 1) * normalizedSize, normalizedSize),
                normalizedPage, normalizedSize, mapper.count(normalizedKeyword));
    }

    @Override
    @Transactional
    public UserView create(UserCreateRequest request) {
        CurrentOperator.requireRole("SUPER_ADMIN");
        String username = request.username().trim();
        if (mapper.selectByUsername(username) != null) {
            throw new BusinessException("USER_EXISTS", "用户名已存在");
        }
        String userType = normalizeUserType(request.userType(), "WEB_ADMIN");
        validateBindings(userType, request.agentId(), request.workstationId());
        UserCreateRequest normalized = new UserCreateRequest(username, request.password(), request.displayName(),
                userType, request.agentId(), request.workstationId());
        if (mapper.insert(normalized, encoder.encode(request.password())) != 1) {
            throw new BusinessException("USER_CREATE_FAILED", "用户创建失败");
        }
        UserView created = mapper.selectByUsername(username);
        if (created == null) {
            throw new BusinessException("USER_CREATE_FAILED", "用户创建后读取失败");
        }
        mapper.insertOperationLog(String.valueOf(created.getId()), "创建用户", CurrentOperator.id(), CurrentOperator.username(),
                null, "username=" + username + ",userType=" + userType, CurrentOperator.clientIp());
        return created;
    }

    @Override
    @Transactional
    public UserView update(long id, UserUpdateRequest request) {
        CurrentOperator.requireRole("SUPER_ADMIN");
        UserView before = mapper.selectById(id);
        if (before == null) {
            throw new BusinessException("USER_NOT_FOUND", "用户不存在");
        }
        String userType = normalizeUserType(request.userType(), before.getUserType());
        validateBindings(userType, request.agentId(), request.workstationId());
        UserUpdateRequest normalized = new UserUpdateRequest(request.displayName(), userType,
                request.agentId(), request.workstationId());
        if (mapper.updateProfile(id, normalized) != 1) {
            throw new BusinessException("USER_UPDATE_FAILED", "用户更新失败");
        }
        UserView updated = mapper.selectById(id);
        if (updated == null) {
            throw new BusinessException("USER_UPDATE_FAILED", "用户更新后读取失败");
        }
        mapper.insertOperationLog(String.valueOf(id), "更新用户", CurrentOperator.id(), CurrentOperator.username(),
                "displayName=" + before.getDisplayName() + ",userType=" + before.getUserType(),
                "displayName=" + updated.getDisplayName() + ",userType=" + updated.getUserType(), CurrentOperator.clientIp());
        return updated;
    }

    @Override
    @Transactional
    public UserView updateStatus(long id, boolean enabled) {
        CurrentOperator.requireRole("SUPER_ADMIN");
        UserView before = mapper.selectById(id);
        if (before == null) {
            throw new BusinessException("USER_NOT_FOUND", "用户不存在");
        }
        if (mapper.updateStatus(id, enabled) != 1) {
            throw new BusinessException("USER_UPDATE_FAILED", "用户状态更新失败");
        }
        UserView updated = mapper.selectById(id);
        mapper.insertOperationLog(String.valueOf(id), "更新用户状态", CurrentOperator.id(), CurrentOperator.username(),
                "enabled=" + before.isEnabled(), "enabled=" + updated.isEnabled(), CurrentOperator.clientIp());
        return updated;
    }

    @Override
    @Transactional
    public UserView updateRoles(long id, UserRoleRequest request) {
        CurrentOperator.requireRole("SUPER_ADMIN");
        if (mapper.selectById(id) == null) {
            throw new BusinessException("USER_NOT_FOUND", "用户不存在");
        }
        if (request.roleIds().stream().anyMatch(java.util.Objects::isNull)
                || request.roleIds().stream().anyMatch(roleId -> roleId <= 0)
                || request.roleIds().stream().distinct().count() != request.roleIds().size()) {
            throw new BusinessException("ROLE_INVALID", "角色 ID 不合法或重复");
        }
        if (!request.roleIds().isEmpty()
                && mapper.countExistingRoles(request.roleIds()) != request.roleIds().stream().distinct().count()) {
            throw new BusinessException("ROLE_INVALID", "存在不存在或已停用的角色");
        }
        mapper.clearRoles(id);
        if (!request.roleIds().isEmpty() && mapper.insertRoles(id, request.roleIds()) != request.roleIds().size()) {
            throw new BusinessException("ROLE_UPDATE_FAILED", "用户角色保存失败");
        }
        UserView updated = mapper.selectById(id);
        mapper.insertOperationLog(String.valueOf(id), "更新用户角色", CurrentOperator.id(), CurrentOperator.username(),
                null, "roleCount=" + request.roleIds().size(), CurrentOperator.clientIp());
        return updated;
    }

    private void validateBindings(String userType, Long agentId, Long workstationId) {
        if ("AGENT".equals(userType) && agentId == null) {
            throw new BusinessException("AGENT_REQUIRED", "代理商账号必须绑定代理商");
        }
        if ("PDA_OPERATOR".equals(userType) && workstationId == null) {
            throw new BusinessException("WORKSTATION_REQUIRED", "PDA 操作员必须绑定工位");
        }
        if (agentId != null && (agentId <= 0 || mapper.countEnabledAgent(agentId) != 1)) {
            throw new BusinessException("AGENT_NOT_FOUND", "代理商不存在或已停用");
        }
        if (workstationId != null && (workstationId <= 0 || mapper.countEnabledWorkstation(workstationId) != 1)) {
            throw new BusinessException("WORKSTATION_NOT_FOUND", "工位不存在或已停用");
        }
        if (!"AGENT".equals(userType) && agentId != null) {
            throw new BusinessException("USER_BINDING_INVALID", "只有代理商账号可以绑定代理商");
        }
        if ("AGENT".equals(userType) && workstationId != null) {
            throw new BusinessException("USER_BINDING_INVALID", "代理商账号不能绑定内部工位");
        }
    }

    private static String normalizeUserType(String value, String defaultValue) {
        String normalized = value == null || value.isBlank() ? defaultValue : value.trim().toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("WEB_ADMIN", "WEB_OPERATOR", "PDA_OPERATOR", "AGENT").contains(normalized)) {
            throw new BusinessException("USER_TYPE_INVALID", "账号类型不支持");
        }
        return normalized;
    }
}
