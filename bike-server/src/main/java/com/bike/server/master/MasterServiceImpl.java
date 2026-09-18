package com.bike.server.master;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.common.OperationLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;

@Service
public class MasterServiceImpl implements MasterService {
    private static final Set<String> TYPES = Set.of("models", "customers", "suppliers", "agents", "warehouses");
    private final MasterMapper masterMapper;
    private final OperationLogMapper operationLogMapper;
    public MasterServiceImpl(MasterMapper masterMapper, OperationLogMapper operationLogMapper) { this.masterMapper = masterMapper; this.operationLogMapper = operationLogMapper; }

    @Override public java.util.List<MasterItem> list(String type, String keyword) {
        validateType(type);
        CurrentOperator.requireAnyPermission("MASTER_READ", "INVENTORY_READ", "ORDER_READ", "AGENT_INVENTORY_READ", "STOCK_IN_OPERATE", "STOCK_OUT_OPERATE");
        if (CurrentOperator.hasRole("PDA_OPERATOR")) {
            CurrentOperator.requireWorkstation(type.equals("customers") ? "STOCK_OUT" : "STOCK_IN");
        } else {
            CurrentOperator.requireWebAccess();
        }
        if (CurrentOperator.hasRole("PDA_OPERATOR") && !Set.of("customers", "warehouses").contains(type)) {
            throw new BusinessException("FORBIDDEN", "PDA 账号只能读取出入库作业所需基础资料");
        }
        if (CurrentOperator.hasRole("WEB_AGENT")) {
            if (!"models".equals(type)) throw new BusinessException("FORBIDDEN", "代理商账号无权访问该基础资料");
            Long agentId = CurrentOperator.agentId();
            if (agentId == null) throw new BusinessException("AGENT_NOT_BOUND", "当前账号未绑定代理商");
            return masterMapper.selectAuthorizedModels(agentId, clean(keyword));
        }
        return masterMapper.select(type, clean(keyword));
    }

    @Override @Transactional
    public MasterItem create(String type, MasterCreateRequest request) {
        CurrentOperator.requireRole("SUPER_ADMIN");
        validateType(type);
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (masterMapper.selectByCode(type, code) != null) throw new BusinessException("MASTER_CODE_EXISTS", "编码已存在");
        masterMapper.insert(type, new MasterCreateRequest(code, request.name().trim(), request.contactName(), request.contactPhone()));
        MasterItem created = masterMapper.selectByCode(type, code);
        operationLogMapper.insert("MASTER_CHANGE", String.valueOf(created.id()), "创建基础资料", CurrentOperator.id(), CurrentOperator.username(), null,
                "{\"type\":\"" + type + "\",\"code\":\"" + code + "\"}", CurrentOperator.clientIp(), null);
        return created;
    }

    @Override @Transactional
    public MasterItem updateStatus(String type, long id, boolean enabled) {
        CurrentOperator.requireRole("SUPER_ADMIN");
        validateType(type);
        if (masterMapper.selectById(type, id) == null) throw new BusinessException("MASTER_NOT_FOUND", "基础资料不存在");
        if (masterMapper.updateStatus(type, id, enabled) != 1) throw new BusinessException("MASTER_UPDATE_FAILED", "基础资料状态更新失败");
        MasterItem updated = masterMapper.selectById(type, id);
        operationLogMapper.insert("MASTER_CHANGE", String.valueOf(id), "更新基础资料状态", CurrentOperator.id(), CurrentOperator.username(), null,
                "{\"type\":\"" + type + "\",\"enabled\":" + enabled + "}", CurrentOperator.clientIp(), null);
        return updated;
    }

    @Override
    @Transactional
    public MasterItem update(String type, long id, MasterUpdateRequest request) {
        CurrentOperator.requireRole("SUPER_ADMIN");
        validateType(type);
        MasterItem before = masterMapper.selectById(type, id);
        if (before == null) throw new BusinessException("MASTER_NOT_FOUND", "基础资料不存在");
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        MasterItem duplicate = masterMapper.selectByCode(type, code);
        if (duplicate != null && !duplicate.id().equals(id)) throw new BusinessException("MASTER_CODE_EXISTS", "编码已存在");
        MasterUpdateRequest normalized = new MasterUpdateRequest(code, request.name().trim(), request.contactName(), request.contactPhone());
        if (masterMapper.update(type, id, normalized) != 1) throw new BusinessException("MASTER_UPDATE_FAILED", "基础资料更新失败");
        MasterItem updated = masterMapper.selectById(type, id);
        operationLogMapper.insert("MASTER_CHANGE", String.valueOf(id), "编辑基础资料", CurrentOperator.id(), CurrentOperator.username(), null,
                "{\"type\":\"" + type + "\",\"code\":\"" + code + "\"}", CurrentOperator.clientIp(), null);
        return updated;
    }

    @Override
    public java.util.List<LocationItem> listLocations(long warehouseId) {
        CurrentOperator.requireAnyPermission("MASTER_READ", "INVENTORY_READ", "STOCK_IN_OPERATE");
        if (CurrentOperator.hasRole("PDA_OPERATOR")) CurrentOperator.requireWorkstation("STOCK_IN");
        else CurrentOperator.requireWebAccess();
        if (CurrentOperator.hasRole("WEB_AGENT")) throw new BusinessException("FORBIDDEN", "代理商账号无权访问仓库库位");
        MasterItem warehouse = warehouseId <= 0 ? null : masterMapper.selectById("warehouses", warehouseId);
        if (warehouse == null) throw new BusinessException("WAREHOUSE_NOT_FOUND", "仓库不存在");
        if (!"ENABLED".equals(warehouse.status())) throw new BusinessException("WAREHOUSE_DISABLED", "仓库已停用，无法读取库位");
        return masterMapper.selectLocations(warehouseId);
    }

    @Override
    @Transactional
    public LocationItem createLocation(long warehouseId, MasterCreateRequest request) {
        CurrentOperator.requireRole("SUPER_ADMIN");
        MasterItem warehouse = warehouseId <= 0 ? null : masterMapper.selectById("warehouses", warehouseId);
        if (warehouse == null) {
            throw new BusinessException("WAREHOUSE_NOT_FOUND", "仓库不存在");
        }
        if (!"ENABLED".equals(warehouse.status())) throw new BusinessException("WAREHOUSE_DISABLED", "仓库已停用，无法创建库位");
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (masterMapper.selectLocationByCode(warehouseId, code) != null) {
            throw new BusinessException("LOCATION_EXISTS", "库位编码已存在");
        }
        if (masterMapper.insertLocation(warehouseId, new MasterCreateRequest(code, request.name().trim(), null, null)) != 1) {
            throw new BusinessException("LOCATION_CREATE_FAILED", "库位创建失败");
        }
        LocationItem created = masterMapper.selectLocationByCode(warehouseId, code);
        operationLogMapper.insert("MASTER_CHANGE", String.valueOf(created.id()), "创建仓库库位", CurrentOperator.id(), CurrentOperator.username(), null,
                "{\"warehouseId\":" + warehouseId + ",\"code\":\"" + code + "\"}", CurrentOperator.clientIp(), null);
        return created;
    }

    @Override
    @Transactional
    public LocationItem updateLocation(long id, LocationUpdateRequest request) {
        CurrentOperator.requireRole("SUPER_ADMIN");
        LocationItem before = requireLocation(id);
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        LocationItem duplicate = masterMapper.selectLocationByCode(before.warehouseId(), code);
        if (duplicate != null && !duplicate.id().equals(id)) {
            throw new BusinessException("LOCATION_EXISTS", "库位编码已存在");
        }
        LocationUpdateRequest normalized = new LocationUpdateRequest(code, request.name().trim());
        if (masterMapper.updateLocation(id, normalized) != 1) {
            throw new BusinessException("LOCATION_UPDATE_FAILED", "库位更新失败");
        }
        LocationItem updated = requireLocation(id);
        operationLogMapper.insert("MASTER_CHANGE", String.valueOf(id), "编辑仓库库位", CurrentOperator.id(), CurrentOperator.username(),
                "{\"warehouseId\":" + before.warehouseId() + ",\"code\":\"" + before.code() + "\"}",
                "{\"warehouseId\":" + updated.warehouseId() + ",\"code\":\"" + updated.code() + "\"}", CurrentOperator.clientIp(), null);
        return updated;
    }

    @Override
    @Transactional
    public LocationItem updateLocationStatus(long id, boolean enabled) {
        CurrentOperator.requireRole("SUPER_ADMIN");
        LocationItem before = requireLocation(id);
        if (enabled) {
            MasterItem warehouse = masterMapper.selectById("warehouses", before.warehouseId());
            if (warehouse == null) throw new BusinessException("WAREHOUSE_NOT_FOUND", "仓库不存在");
            if (!"ENABLED".equals(warehouse.status())) throw new BusinessException("WAREHOUSE_DISABLED", "仓库已停用，无法启用库位");
        }
        if (masterMapper.updateLocationStatus(id, enabled) != 1) {
            throw new BusinessException("LOCATION_UPDATE_FAILED", "库位状态更新失败");
        }
        LocationItem updated = requireLocation(id);
        operationLogMapper.insert("MASTER_CHANGE", String.valueOf(id), "更新库位状态", CurrentOperator.id(), CurrentOperator.username(),
                "{\"enabled\":" + ("ENABLED".equals(before.status())) + "}",
                "{\"enabled\":" + enabled + "}", CurrentOperator.clientIp(), null);
        return updated;
    }

    private LocationItem requireLocation(long id) {
        LocationItem location = masterMapper.selectLocationById(id);
        if (location == null) throw new BusinessException("LOCATION_NOT_FOUND", "库位不存在");
        return location;
    }

    private static void validateType(String type) { if (!TYPES.contains(type)) throw new BusinessException("MASTER_TYPE_NOT_SUPPORTED", "不支持的基础资料类型"); }
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
