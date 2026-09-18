package com.bike.server.system;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SystemConfigServiceImpl implements SystemConfigService {
    private final SystemConfigMapper mapper;

    public SystemConfigServiceImpl(SystemConfigMapper mapper) { this.mapper = mapper; }

    @Override public List<SystemConfigView> list() { requireAdmin(); return mapper.selectAll(); }

    @Override public SystemConfigView get(String configKey) {
        requireAdmin();
        SystemConfigView value = mapper.selectByKey(configKey);
        if (value == null) throw new BusinessException("CONFIG_NOT_FOUND", "配置不存在");
        return value;
    }

    @Override @Transactional
    public SystemConfigView update(String configKey, SystemConfigRequest request) {
        requireAdmin();
        if (configKey == null || configKey.isBlank() || configKey.length() > 128) throw new BusinessException("CONFIG_KEY_INVALID", "配置键不合法");
        String normalizedKey = configKey.trim();
        SystemConfigView before = mapper.selectByKey(normalizedKey);
        mapper.upsert(normalizedKey, request, CurrentOperator.id());
        mapper.insertOperationLog(normalizedKey, CurrentOperator.id(), CurrentOperator.username(), before == null ? null : before.configValue(), request.configValue(), CurrentOperator.clientIp());
        return get(normalizedKey);
    }

    private static void requireAdmin() {
        CurrentOperator.requireRole("SUPER_ADMIN");
    }
}
