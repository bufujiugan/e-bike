package com.bike.server.assembly;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.vehicle.VehicleMapper;
import com.bike.server.vehicle.VehicleView;
import com.bike.server.validation.SnRuleService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class AssemblyServiceImpl implements AssemblyService {
    private final VehicleMapper vehicleMapper;
    private final AssemblyMapper assemblyMapper;
    private final SnRuleService snRuleService;
    @Autowired private com.bike.server.production.ProductionGuard productionGuard;

    public AssemblyServiceImpl(VehicleMapper vehicleMapper, AssemblyMapper assemblyMapper) {
        this(vehicleMapper, assemblyMapper, new com.bike.server.validation.SnRuleServiceImpl(null));
    }

    @Autowired
    public AssemblyServiceImpl(VehicleMapper vehicleMapper, AssemblyMapper assemblyMapper, SnRuleService snRuleService) {
        this.vehicleMapper = vehicleMapper;
        this.assemblyMapper = assemblyMapper;
        this.snRuleService = snRuleService;
    }

    @Override
    public AssemblyValidationResult validateFrame(String frameSn) {
        CurrentOperator.requirePermission("ASSEMBLY_OPERATE");
        com.bike.server.production.ProductionAccess.scan("FRAME");
        VehicleView vehicle = vehicleMapper.selectByFrame(snRuleService.normalizeFrameSn(frameSn));
        if (vehicle == null) return new AssemblyValidationResult(false, frameSn, "FRAME", "NOT_FOUND", "车架不存在");
        if (vehicle.getStatus() == null || vehicle.getStatus() != 0) {
            return new AssemblyValidationResult(false, frameSn, "FRAME", String.valueOf(vehicle.getStatus()), "车架当前状态不允许组装");
        }
        return new AssemblyValidationResult(true, vehicle.getFrameSn(), "FRAME", "AVAILABLE", "车架校验通过");
    }

    @Override
    public AssemblyValidationResult validatePart(AssemblyScanRequest request) {
        CurrentOperator.requirePermission("ASSEMBLY_OPERATE");
        com.bike.server.production.ProductionAccess.scan(request.expectedPartType().trim().toUpperCase(Locale.ROOT));
        String expectedType = request.expectedPartType().trim().toUpperCase(Locale.ROOT);
        String partSn = snRuleService.normalizePartSn(request.partSn(), expectedType);
        AssemblyPartRecord part = assemblyMapper.selectPartForUpdate(partSn);
        if (part == null) return new AssemblyValidationResult(false, partSn, expectedType, "NOT_FOUND", "零部件不存在");
        if (!expectedType.equals(part.getPartType())) {
            return new AssemblyValidationResult(false, partSn, part.getPartType(), part.getStatus(), "当前应扫描" + expectedType + "二维码，检测到" + part.getPartType() + "二维码，请重新扫描。");
        }
        if (!"AVAILABLE".equals(part.getStatus()) || !"UNBOUND".equals(part.getBindStatus())) {
            return new AssemblyValidationResult(false, partSn, part.getPartType(), part.getStatus(), "零部件当前不可用或已绑定");
        }
        return new AssemblyValidationResult(true, partSn, part.getPartType(), part.getStatus(), "零部件校验通过");
    }

    @Override
    @Transactional
    public VehicleView submit(AssemblySubmitRequest request) {
        CurrentOperator.requirePermission("ASSEMBLY_OPERATE");
        CurrentOperator.requireWorkstation("ASSEMBLY");
        return completeBinding(request);
    }

    @Override
    @Transactional
    public VehicleView submitCollected(String taskId, AssemblySubmitRequest request) {
        com.bike.server.production.ProductionAccess.operate();
        productionGuard.requireCollectedTask(taskId, request);
        return completeBinding(request);
    }

    private VehicleView completeBinding(AssemblySubmitRequest request) {
        if (productionGuard != null) productionGuard.lock();
        String frameSn = snRuleService.normalizeFrameSn(request.frameSn());
        if (productionGuard != null) productionGuard.requireCodeAvailable(frameSn, null);
        VehicleView vehicle = vehicleMapper.selectByFrameForUpdate(frameSn);
        if (vehicle == null) throw new BusinessException("VEHICLE_NOT_FOUND", "车架不存在");
        if (vehicle.getStatus() != 0) throw new BusinessException("INVALID_STATUS_TRANSITION", "整车当前状态不允许组装");
        String operatorName = CurrentOperator.username();
        Long operatorId = CurrentOperator.id();
        bindPart(vehicle, request.motorSn(), "MOTOR", operatorId, operatorName);
        bindPart(vehicle, request.batterySn(), "BATTERY", operatorId, operatorName);
        bindPart(vehicle, request.displaySn(), "DISPLAY", operatorId, operatorName);
        String token = UUID.randomUUID().toString().replace("-", "");
        if (vehicleMapper.updateAssembled(vehicle.getId(), operatorId, "BIKE:" + vehicle.getId() + ":" + token, token, 1) != 1) {
            throw new BusinessException("INVALID_STATUS_TRANSITION", "整车状态更新失败");
        }
        assemblyMapper.insertOperationLog(String.valueOf(vehicle.getId()), "四码绑定", frameSn, operatorId, operatorName, "组装提交", CurrentOperator.clientIp());
        return vehicleMapper.selectByFrame(frameSn);
    }

    @Override
    public VehicleView getByFrame(String frameSn) {
        CurrentOperator.requireAnyPermission("ASSEMBLY_OPERATE", "VEHICLE_READ", "TRACE_READ");
        if (CurrentOperator.hasRole("PDA_OPERATOR")) CurrentOperator.requireWorkstation("ASSEMBLY");
        else CurrentOperator.requireWebAccess();
        VehicleView vehicle = vehicleMapper.selectByFrame(snRuleService.normalizeFrameSn(frameSn));
        if (vehicle == null) throw new BusinessException("VEHICLE_NOT_FOUND", "车架不存在");
        return vehicle;
    }

    private void bindPart(VehicleView vehicle, String rawPartSn, String expectedType, Long operatorId, String operatorName) {
        String partSn = snRuleService.normalizePartSn(rawPartSn, expectedType);
        if (productionGuard != null) productionGuard.requireCodeAvailable(partSn, null);
        AssemblyPartRecord part = assemblyMapper.selectPartForUpdate(partSn);
        if (part == null) throw new BusinessException("PART_NOT_FOUND", "零部件不存在: " + partSn);
        if (!expectedType.equals(part.getPartType())) throw new BusinessException("PART_TYPE_MISMATCH", "零部件类型不匹配");
        if (!"UNBOUND".equals(part.getBindStatus())) throw new BusinessException("PART_ALREADY_BOUND", "零部件已被其他整车绑定");
        if (!"AVAILABLE".equals(part.getStatus())) throw new BusinessException("PART_NOT_AVAILABLE", "零部件当前不可用");
        if (part.getQcStatus() != null && !"PASSED".equals(part.getQcStatus())) throw new BusinessException("PART_QC_FAILED", "零部件尚未质量放行");
        try {
            assemblyMapper.insertBinding(vehicle.getId(), vehicle.getFrameSn(), part.getId(), part.getPartSn(), part.getPartType(), operatorId, operatorName);
            if (assemblyMapper.markPartBound(part.getId(), vehicle.getId(), vehicle.getFrameSn()) != 1) throw new BusinessException("PART_ALREADY_BOUND", "零部件状态更新失败");
            if (assemblyMapper.insertPartBindingLog(part.getPartSn(), vehicle.getFrameSn(), operatorId, operatorName, CurrentOperator.clientIp()) != 1) {
                throw new BusinessException("AUDIT_LOG_FAILED", "零部件绑定日志写入失败");
            }
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException("PART_ALREADY_BOUND", "零部件已被其他工位绑定");
        }
    }

}
