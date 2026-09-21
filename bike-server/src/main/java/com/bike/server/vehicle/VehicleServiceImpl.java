package com.bike.server.vehicle;

import com.bike.server.common.BusinessException;
import com.bike.server.common.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.time.LocalDate;
import com.bike.server.validation.SnRuleService;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class VehicleServiceImpl implements VehicleService {
    private final VehicleMapper vehicleMapper;
    private final SnRuleService snRuleService;
    @Autowired private com.bike.server.production.ProductionGuard productionGuard;

    public VehicleServiceImpl(VehicleMapper vehicleMapper) { this(vehicleMapper, new com.bike.server.validation.SnRuleServiceImpl(null)); }
    @Autowired
    public VehicleServiceImpl(VehicleMapper vehicleMapper, SnRuleService snRuleService) { this.vehicleMapper = vehicleMapper; this.snRuleService = snRuleService; }

    @Override
    public PageResponse<VehicleView> list(String keyword, Integer status, Long modelId, Long customerId, String orderNo,
                                          LocalDate fromDate, LocalDate toDate, long page, long pageSize) {
        com.bike.server.common.CurrentOperator.requireInternalWebAccess(); com.bike.server.common.CurrentOperator.requireAnyPermission("VEHICLE_READ", "INVENTORY_READ", "TRACE_READ");
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new BusinessException("INVALID_PARAMETER", "开始日期不能晚于结束日期");
        }
        long normalizedPage = Math.max(1, page);
        long normalizedSize = Math.min(100, Math.max(1, pageSize));
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        String normalizedOrderNo = orderNo == null || orderNo.isBlank() ? null : orderNo.trim();
        return new PageResponse<>(vehicleMapper.selectPage(normalizedKeyword, status, modelId, customerId, normalizedOrderNo,
                        fromDate, toDate, (normalizedPage - 1) * normalizedSize, normalizedSize), normalizedPage, normalizedSize,
                vehicleMapper.count(normalizedKeyword, status, modelId, customerId, normalizedOrderNo, fromDate, toDate));
    }

    @Override public VehicleView getById(long id) { com.bike.server.common.CurrentOperator.requireInternalWebAccess(); com.bike.server.common.CurrentOperator.requireAnyPermission("VEHICLE_READ", "INVENTORY_READ", "TRACE_READ"); return require(vehicleMapper.selectById(id)); }
    @Override public VehicleView getByFrame(String frameSn) { com.bike.server.common.CurrentOperator.requireInternalWebAccess(); com.bike.server.common.CurrentOperator.requireAnyPermission("VEHICLE_READ", "INVENTORY_READ", "TRACE_READ"); return require(vehicleMapper.selectByFrame(snRuleService.normalizeFrameSn(frameSn))); }
    @Override public VehicleView getByQr(String qrCode) { com.bike.server.common.CurrentOperator.requireInternalWebAccess(); com.bike.server.common.CurrentOperator.requireAnyPermission("VEHICLE_READ", "INVENTORY_READ", "TRACE_READ"); return require(vehicleMapper.selectByQr(normalizeQr(qrCode))); }

    @Override
    @Transactional
    public VehicleView create(VehicleCreateRequest request) {
        com.bike.server.common.CurrentOperator.requireRole("SUPER_ADMIN");
        String frameSn = snRuleService.normalizeFrameSn(request.frameSn());
        if (vehicleMapper.selectByFrame(frameSn) != null) throw new BusinessException("FRAME_ALREADY_EXISTS", "车架 SN 已存在");
        if (request.modelId() != null && vehicleMapper.countEnabledModel(request.modelId()) != 1) {
            throw new BusinessException("MODEL_NOT_FOUND", "车型不存在或已停用");
        }
        if (vehicleMapper.insert(new VehicleCreateRequest(frameSn, request.modelId())) != 1) throw new BusinessException("VEHICLE_CREATE_FAILED", "整车建档失败");
        VehicleView created = vehicleMapper.selectByFrame(frameSn);
        vehicleMapper.insertOperationLog(String.valueOf(created.getId()), "创建整车档案", com.bike.server.common.CurrentOperator.id(), com.bike.server.common.CurrentOperator.username(), null, "frameSn=" + frameSn, com.bike.server.common.CurrentOperator.clientIp());
        return created;
    }

    @Override
    @Transactional
    public VehicleView regenerateQr(long id) {
        com.bike.server.common.CurrentOperator.requireRole("SUPER_ADMIN");
        if(productionGuard!=null){productionGuard.lock();productionGuard.invalidatePendingLabels(id);}
        VehicleView vehicle = getById(id);
        String token = UUID.randomUUID().toString().replace("-", "");
        String qrCode = "BIKE:" + id + ":" + token;
        if (vehicleMapper.updateQr(id, qrCode, token, vehicle.getQrVersion() == null ? 1 : vehicle.getQrVersion() + 1) != 1) throw new BusinessException("QR_UPDATE_FAILED", "二维码更新失败");
        vehicleMapper.insertOperationLog(String.valueOf(id), "重新生成整车二维码", com.bike.server.common.CurrentOperator.id(), com.bike.server.common.CurrentOperator.username(), "qrVersion=" + vehicle.getQrVersion(), "qrVersion=" + (vehicle.getQrVersion() == null ? 1 : vehicle.getQrVersion() + 1), com.bike.server.common.CurrentOperator.clientIp());
        return getById(id);
    }

    private static VehicleView require(VehicleView vehicle) {
        if (vehicle == null) throw new BusinessException("VEHICLE_NOT_FOUND", "整车不存在");
        return vehicle;
    }

    private static String normalizeQr(String value) {
        if (value == null || value.isBlank()) throw new BusinessException("INVALID_QR_CODE", "查询标识不能为空");
        return value.trim();
    }
}
