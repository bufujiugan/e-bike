package com.bike.server.production;

import com.bike.server.assembly.AssemblySubmitRequest;
import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.validation.SnRuleService;
import com.bike.server.vehicle.VehicleMapper;
import com.bike.server.vehicle.VehicleView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 旧四码一次提交接口适配到统一生产任务，保留原请求/响应结构。 */
@Service
public class LegacyAssemblyService {
    private final ProductionService production;
    private final ProductionGuard guard;
    private final ProductionMapper mapper;
    private final VehicleMapper vehicles;
    private final SnRuleService rules;
    public LegacyAssemblyService(ProductionService production, ProductionGuard guard, ProductionMapper mapper,
                                 VehicleMapper vehicles, SnRuleService rules) {
        this.production=production; this.guard=guard; this.mapper=mapper; this.vehicles=vehicles; this.rules=rules;
    }

    @Transactional
    public VehicleView submit(AssemblySubmitRequest request) {
        CurrentOperator.requirePermission("ASSEMBLY_OPERATE");
        CurrentOperator.requireWorkstation("ASSEMBLY");
        guard.lock();
        String frame=rules.normalizeFrameSn(request.frameSn());
        VehicleView vehicle=vehicles.selectByFrameForUpdate(frame);
        if(vehicle==null) throw new BusinessException("VEHICLE_NOT_FOUND","车架不存在");
        String taskId="LEGACY-"+UUID.nameUUIDFromBytes(frame.getBytes(StandardCharsets.UTF_8));
        Map<String,Object> existing=mapper.selectTask(taskId);
        Map<String,String> codes=Map.of("FRAME",frame,"MOTOR",rules.normalizePartSn(request.motorSn(),"MOTOR"),
                "BATTERY",rules.normalizePartSn(request.batterySn(),"BATTERY"),"DISPLAY",rules.normalizePartSn(request.displaySn(),"DISPLAY"));
        if(existing!=null && "COMPLETED".equals(existing.get("status"))) {
            List<Map<String,Object>> scans=mapper.selectScans(taskId);
            if(scans.size()!=4 || !scans.stream().map(scan->scan.get("codeType")).collect(java.util.stream.Collectors.toSet()).equals(codes.keySet()) ||
                scans.stream().anyMatch(scan->!java.util.Objects.equals(codes.get(scan.get("codeType")),scan.get("code"))))
                throw new BusinessException("IDEMPOTENCY_CONFLICT","已完成任务的四码不能更改，请走返工流程");
            return vehicles.selectByFrame(frame);
        }
        if(vehicle.getStatus()!=0) throw new BusinessException("INVALID_STATUS_TRANSITION","车辆当前状态不允许组装");
        List<Map<String,Object>> skus=mapper.selectSkus().stream().filter(sku->
                ((Number)sku.get("modelId")).longValue()==vehicle.getModelId() &&
                (Boolean.TRUE.equals(sku.get("enabled")) || (sku.get("enabled") instanceof Number enabled && enabled.intValue()==1))).toList();
        if(skus.size()!=1) throw new BusinessException("SKU_SELECTION_REQUIRED","该车型没有唯一启用SKU，请在生产任务页面选择SKU后扫码");
        long skuId=((Number)skus.getFirst().get("id")).longValue();
        production.createTask(new ProductionContracts.TaskRequest(taskId,skuId,null));
        for(String type:List.of("FRAME","MOTOR","BATTERY","DISPLAY"))
            production.scan(taskId,new ProductionContracts.ScanRequest(codes.get(type),type));
        return vehicles.selectByFrame(frame);
    }
}
