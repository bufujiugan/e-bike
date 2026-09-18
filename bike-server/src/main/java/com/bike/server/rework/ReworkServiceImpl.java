package com.bike.server.rework;

import com.bike.server.assembly.AssemblyPartRecord;
import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.common.PageResponse;
import com.bike.server.vehicle.VehicleView;
import com.bike.server.validation.SnRuleService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Locale;
import java.util.UUID;

@Service
public class ReworkServiceImpl implements ReworkService {
    private final ReworkMapper mapper;
    private final SnRuleService snRuleService;
    @Autowired private com.bike.server.production.ProductionReworkGuard productionGuard;
    public ReworkServiceImpl(ReworkMapper mapper){this(mapper, new com.bike.server.validation.SnRuleServiceImpl(null));}
    @Autowired
    public ReworkServiceImpl(ReworkMapper mapper, SnRuleService snRuleService){this.mapper=mapper;this.snRuleService=snRuleService;}
    @Override @Transactional public ReworkView create(ReworkRequest request){CurrentOperator.requirePermission("REWORK_OPERATE");CurrentOperator.requireWorkstation("REWORK");if(productionGuard!=null)productionGuard.beforeReplace(request);String type=request.partType().trim().toUpperCase(Locale.ROOT);if(type.isBlank())throw new BusinessException("PART_TYPE_MISMATCH","返工部件类型不能为空");String frame=snRuleService.normalizeFrameSn(request.frameSn()),newPartSn=snRuleService.normalizePartSn(request.newPartSn(),type);VehicleView vehicle=mapper.selectVehicleForUpdate(frame);if(vehicle==null)throw new BusinessException("VEHICLE_NOT_FOUND","整车不存在");if(vehicle.getStatus()==null||!(vehicle.getStatus()==1||vehicle.getStatus()==2||vehicle.getStatus()==5))throw new BusinessException("INVALID_STATUS_TRANSITION","当前整车状态不允许返工");AssemblyPartRecord oldPart=mapper.selectCurrentPartForUpdate(vehicle.getId(),type),newPart=mapper.selectNewPartForUpdate(newPartSn);if(oldPart==null)throw new BusinessException("PART_NOT_FOUND","当前部件不存在");if(newPart==null)throw new BusinessException("PART_NOT_FOUND","新零部件不存在");if(oldPart.getPartSn().equals(newPart.getPartSn()))throw new BusinessException("PART_ALREADY_BOUND","新旧零部件不能相同");if(!type.equals(newPart.getPartType()))throw new BusinessException("PART_TYPE_MISMATCH","新零部件类型不匹配");if(!"UNBOUND".equals(newPart.getBindStatus()))throw new BusinessException("PART_ALREADY_BOUND","新零部件已被其他整车绑定");if(!"AVAILABLE".equals(newPart.getStatus()))throw new BusinessException("PART_NOT_AVAILABLE","新零部件当前不可用");String no="RW"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase(),operator=CurrentOperator.username();Long operatorId=CurrentOperator.id();if(mapper.updateVehicleStatus(vehicle.getId(),4)!=1)throw new BusinessException("INVALID_STATUS_TRANSITION","整车进入返工状态失败");mapper.insertRework(no,vehicle.getId(),frame,request.reason().trim(),operatorId);mapper.insertDetail(no,type,oldPart.getId(),oldPart.getPartSn(),newPart.getId(),newPart.getPartSn());if(mapper.unbindOld(vehicle.getId(),type,request.reason(),operatorId)<1||mapper.releaseOldPart(oldPart.getId())!=1)throw new BusinessException("REWORK_UPDATE_FAILED","旧零部件解绑失败");if(mapper.insertPartUnbindOperationLog(oldPart.getPartSn(),type,frame,operatorId,operator,request.reason(),CurrentOperator.clientIp())!=1)throw new BusinessException("AUDIT_LOG_FAILED","零部件解绑日志写入失败");try{if(mapper.bindNew(vehicle.getId(),frame,newPart.getId(),newPart.getPartSn(),type,request.reason(),operatorId)!=1||mapper.occupyNewPart(newPart.getId(),vehicle.getId(),frame)!=1)throw new BusinessException("PART_ALREADY_BOUND","新零部件占用失败");}catch(DataIntegrityViolationException e){throw new BusinessException("PART_ALREADY_BOUND","新零部件已被其他工位占用");}if(mapper.insertPartBindOperationLog(newPart.getPartSn(),type,frame,operatorId,operator,request.reason(),CurrentOperator.clientIp())!=1)throw new BusinessException("AUDIT_LOG_FAILED","零部件绑定日志写入失败");if(mapper.updateVehicleStatus(vehicle.getId(),vehicle.getStatus())!=1)throw new BusinessException("INVALID_STATUS_TRANSITION","整车返工状态恢复失败");mapper.insertOperationLog(no,frame,type,oldPart.getPartSn(),newPart.getPartSn(),operatorId,operator,request.reason(),CurrentOperator.clientIp());return mapper.selectById(mapper.selectReworkIdByNo(no));}
    @Override public ReworkView get(long id){requireReadAccess();ReworkView v=mapper.selectById(id);if(v==null)throw new BusinessException("REWORK_NOT_FOUND","返工记录不存在");return v;}
    @Override public PageResponse<ReworkView> list(long page,long pageSize){requireReadAccess();long p=Math.max(1,page),s=Math.min(100,Math.max(1,pageSize));return new PageResponse<>(mapper.selectPage((p-1)*s,s),p,s,mapper.count());}
    private static void requireReadAccess(){CurrentOperator.requireAnyPermission("REWORK_OPERATE","INVENTORY_READ");if(CurrentOperator.hasRole("PDA_OPERATOR"))CurrentOperator.requireWorkstation("REWORK");else CurrentOperator.requireWebAccess();}
}
