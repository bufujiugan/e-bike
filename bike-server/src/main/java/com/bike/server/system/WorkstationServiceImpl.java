package com.bike.server.system;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.common.OperationLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class WorkstationServiceImpl implements WorkstationService {
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "FRAME", "MOTOR", "BATTERY", "DISPLAY", "LIGHT",
            "ASSEMBLY", "STOCK_IN", "STOCK_OUT", "REWORK");
    private final WorkstationMapper mapper;
    private final OperationLogMapper operationLogMapper;

    public WorkstationServiceImpl(WorkstationMapper mapper, OperationLogMapper operationLogMapper) {
        this.mapper = mapper;
        this.operationLogMapper = operationLogMapper;
    }

    @Override
    public List<WorkstationView> list(String keyword) {
        CurrentOperator.requireRole("SUPER_ADMIN");
        return mapper.select(keyword);
    }

    @Override
    @Transactional
    public WorkstationView create(WorkstationRequest request) {
        CurrentOperator.requireRole("SUPER_ADMIN");
        WorkstationRequest normalized = normalize(request);
        if (mapper.selectByCode(normalized.workstationCode()) != null) throw new BusinessException("WORKSTATION_EXISTS", "工位编码已存在");
        if (mapper.insert(normalized) != 1) throw new BusinessException("WORKSTATION_CREATE_FAILED", "工位创建失败");
        WorkstationView created = mapper.selectByCode(normalized.workstationCode());
        operationLogMapper.insert("WORKSTATION_CHANGE", String.valueOf(created.getId()), "创建工位", CurrentOperator.id(), CurrentOperator.username(), null,
                "{\"workstationCode\":\"" + normalized.workstationCode() + "\",\"workstationType\":\"" + normalized.workstationType() + "\"}", CurrentOperator.clientIp(), null);
        return created;
    }

    @Override
    @Transactional
    public WorkstationView update(long id, WorkstationRequest request) {
        CurrentOperator.requireRole("SUPER_ADMIN");
        WorkstationRequest normalized = normalize(request);
        WorkstationView before = mapper.selectById(id);
        if (before == null) throw new BusinessException("WORKSTATION_NOT_FOUND", "工位不存在");
        WorkstationView duplicate = mapper.selectByCode(normalized.workstationCode());
        if (duplicate != null && !duplicate.getId().equals(id)) throw new BusinessException("WORKSTATION_EXISTS", "工位编码已存在");
        if (mapper.update(id, normalized) != 1) throw new BusinessException("WORKSTATION_UPDATE_FAILED", "工位更新失败");
        WorkstationView updated = mapper.selectById(id);
        operationLogMapper.insert("WORKSTATION_CHANGE", String.valueOf(id), "更新工位", CurrentOperator.id(), CurrentOperator.username(),
                "{\"workstationCode\":\"" + before.getWorkstationCode() + "\",\"workstationType\":\"" + before.getWorkstationType() + "\"}",
                "{\"workstationCode\":\"" + updated.getWorkstationCode() + "\",\"workstationType\":\"" + updated.getWorkstationType() + "\"}", CurrentOperator.clientIp(), null);
        return updated;
    }

    @Override
    @Transactional
    public WorkstationView updateStatus(long id, boolean enabled) {
        CurrentOperator.requireRole("SUPER_ADMIN");
        WorkstationView before = mapper.selectById(id);
        if (before == null) throw new BusinessException("WORKSTATION_NOT_FOUND", "工位不存在");
        if (mapper.updateStatus(id, enabled) != 1) throw new BusinessException("WORKSTATION_UPDATE_FAILED", "工位状态更新失败");
        WorkstationView updated = mapper.selectById(id);
        operationLogMapper.insert("WORKSTATION_CHANGE", String.valueOf(id), "更新工位状态", CurrentOperator.id(), CurrentOperator.username(),
                "{\"enabled\":" + before.isEnabled() + "}", "{\"enabled\":" + updated.isEnabled() + "}", CurrentOperator.clientIp(), null);
        return updated;
    }

    private static WorkstationRequest normalize(WorkstationRequest request) {
        String code = request.workstationCode().trim().toUpperCase(Locale.ROOT);
        String name = request.workstationName().trim();
        String type = request.workstationType().trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(type)) {
            throw new BusinessException("WORKSTATION_TYPE_INVALID", "工位类型不在系统支持范围内");
        }
        return new WorkstationRequest(code, name, type);
    }
}
