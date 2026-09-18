package com.bike.server.system;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.common.OperationLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RoleServiceImpl implements RoleService {
    private final RoleMapper mapper;
    private final OperationLogMapper operationLogMapper;

    public RoleServiceImpl(RoleMapper mapper, OperationLogMapper operationLogMapper) {
        this.mapper = mapper;
        this.operationLogMapper = operationLogMapper;
    }

    @Override
    public List<RoleView> list(String keyword) {
        CurrentOperator.requireRole("SUPER_ADMIN");
        return mapper.selectPage(keyword);
    }

    @Override
    @Transactional
    public RoleView create(RoleCreateRequest request) {
        CurrentOperator.requireRole("SUPER_ADMIN");
        if (mapper.selectByCode(request.roleCode()) != null) throw new BusinessException("ROLE_EXISTS", "角色编码已存在");
        if (mapper.insert(request) != 1) throw new BusinessException("ROLE_CREATE_FAILED", "角色创建失败");
        RoleView created = mapper.selectByCode(request.roleCode());
        operationLogMapper.insert("ROLE_CHANGE", String.valueOf(created.getId()), "创建角色", CurrentOperator.id(), CurrentOperator.username(), null,
                "{\"roleCode\":\"" + request.roleCode() + "\"}", CurrentOperator.clientIp(), null);
        return created;
    }

    @Override
    @Transactional
    public RoleView update(long id, RoleCreateRequest request) {
        CurrentOperator.requireRole("SUPER_ADMIN");
        RoleView current = require(id);
        RoleView duplicate = mapper.selectByCode(request.roleCode());
        if (duplicate != null && !duplicate.getId().equals(id)) throw new BusinessException("ROLE_EXISTS", "角色编码已存在");
        if (mapper.update(id, request) != 1) throw new BusinessException("ROLE_UPDATE_FAILED", "角色更新失败");
        RoleView updated = mapper.selectById(id);
        operationLogMapper.insert("ROLE_CHANGE", String.valueOf(id), "更新角色", CurrentOperator.id(), CurrentOperator.username(),
                "{\"roleCode\":\"" + current.getRoleCode() + "\"}", "{\"roleCode\":\"" + updated.getRoleCode() + "\"}", CurrentOperator.clientIp(), null);
        return updated;
    }

    @Override
    @Transactional
    public RoleView updateStatus(long id, boolean enabled) {
        CurrentOperator.requireRole("SUPER_ADMIN");
        RoleView before = require(id);
        if (mapper.updateStatus(id, enabled) != 1) throw new BusinessException("ROLE_UPDATE_FAILED", "角色状态更新失败");
        RoleView updated = mapper.selectById(id);
        operationLogMapper.insert("ROLE_CHANGE", String.valueOf(id), "更新角色状态", CurrentOperator.id(), CurrentOperator.username(),
                "{\"enabled\":" + before.isEnabled() + "}", "{\"enabled\":" + updated.isEnabled() + "}", CurrentOperator.clientIp(), null);
        return updated;
    }

    @Override
    public List<PermissionView> permissions(long roleId) {
        CurrentOperator.requireRole("SUPER_ADMIN");
        require(roleId);
        return mapper.selectPermissions(roleId);
    }

    @Override
    public List<PermissionView> allPermissions() {
        CurrentOperator.requireRole("SUPER_ADMIN");
        return mapper.selectAllPermissions();
    }

    @Override
    @Transactional
    public List<PermissionView> updatePermissions(long roleId, RolePermissionRequest request) {
        CurrentOperator.requireRole("SUPER_ADMIN");
        require(roleId);
        if (request.permissionIds().stream().anyMatch(java.util.Objects::isNull)
                || request.permissionIds().stream().anyMatch(id -> id <= 0)) {
            throw new BusinessException("PERMISSION_INVALID", "权限 ID 必须为正数");
        }
        List<Long> permissionIds = request.permissionIds().stream().distinct().toList();
        if (!permissionIds.isEmpty() && mapper.countExistingPermissions(permissionIds) != permissionIds.size()) {
            throw new BusinessException("PERMISSION_INVALID", "存在无效的权限 ID");
        }
        mapper.clearPermissions(roleId);
        if (!permissionIds.isEmpty() && mapper.insertPermissions(roleId, permissionIds) != permissionIds.size()) {
            throw new BusinessException("PERMISSION_UPDATE_FAILED", "角色权限保存失败");
        }
        operationLogMapper.insert("PERMISSION_CHANGE", String.valueOf(roleId), "更新角色权限", CurrentOperator.id(), CurrentOperator.username(), null,
                "{\"permissionCount\":" + permissionIds.size() + "}", CurrentOperator.clientIp(), null);
        return mapper.selectPermissions(roleId);
    }

    private RoleView require(long id) {
        RoleView role = mapper.selectById(id);
        if (role == null) throw new BusinessException("ROLE_NOT_FOUND", "角色不存在");
        return role;
    }
}
