package com.bike.server.aftersales;

import static com.bike.server.aftersales.AfterSalesAccess.*;
import static com.bike.server.aftersales.AfterSalesShippingDetails.*;
import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AfterSalesLogisticsService {
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private final AfterSalesMapper mapper;
    public AfterSalesLogisticsService(AfterSalesMapper mapper){this.mapper=mapper;}

    @Transactional public AfterSalesShipmentView act(long caseId,long shipmentId,AfterSalesLogisticsRequest request) {
        requireInternal("AFTER_SALES_LOGISTICS");
        var c=mapper.selectForUpdate(caseId);requireCaseScope(mapper,c);
        if(!Set.of("APPROVED","IN_PROGRESS","WAITING_PARTS","SHIPPED","RESOLVED").contains(c.getStatus()))throw state();
        var before=mapper.selectShipmentById(shipmentId);
        if(before==null||!Objects.equals(before.getCaseId(),caseId))throw new BusinessException("AFTER_SALES_SHIPMENT_NOT_FOUND","补发记录不存在");
        if(request.revision()!=before.getRevision())throw conflict();
        String action=text(request.action(),32,true);
        String note=text(request.note(),1000,!"SAVE".equals(action));
        var normalized=new AfterSalesLogisticsRequest(before.getRevision(),action,null,null,null,null,note,request.expectedArrivalDate());
        switch(action) {
            case "SAVE" -> {
                if(!"PLANNED".equals(before.getStatus())||request.shipping()==null)throw state();
                changed(mapper.saveShippingDetails(shipmentId,request.shipping().normalized(false)));
                changed(mapper.updateShipmentLogistics(shipmentId,normalized));
            }
            case "TRACK", "CORRECT" -> {
                if(!"SHIPPED".equals(before.getStatus()))throw state();
                var eta=request.expectedArrivalDate();
                if(eta!=null&&(eta.getYear()<2000||eta.getYear()>9998||eta.isBefore(before.getShippedAt().toLocalDate())))throw invalid("预计到达日期不能早于发货日期");
                String status=text(request.logisticsStatus(),32,"TRACK".equals(action));
                if("TRACK".equals(action)) {
                    if(!Set.of("IN_TRANSIT","EXCEPTION","DELIVERED").contains(status))throw invalid("物流状态不合法");
                    if("DELIVERED".equals(before.getLogisticsStatus()))throw state();
                }
                normalized=new AfterSalesLogisticsRequest(before.getRevision(),action,null,
                    text(request.carrier(),128,"CORRECT".equals(action)),text(request.trackingNo(),128,"CORRECT".equals(action)),status,note,eta);
                changed(mapper.updateShipmentLogistics(shipmentId,normalized));
            }
            case "CANCEL" -> {
                if(!"PLANNED".equals(before.getStatus()))throw state();
                var returned=mapper.selectReturn(shipmentId);
                if(returned!=null&&!Set.of("REQUIRED","WAIVED").contains(returned.status()))throw new BusinessException("AFTER_SALES_RETURN_STARTED","旧件退回已启动，不能直接取消补发计划");
                changed(mapper.cancelShipment(shipmentId,before.getRevision(),note));
                if(returned!=null)changed(mapper.cancelShipmentReturn(shipmentId,note,CurrentOperator.id()));
                String next=mapper.countPendingShipments(caseId)>0?"WAITING_PARTS":mapper.countShippedShipments(caseId)>0?"SHIPPED":"IN_PROGRESS";
                changed(mapper.updateStatus(caseId,c.getStatus(),next,null,CurrentOperator.id()));
            }
            default -> throw invalid("物流操作不合法");
        }
        var after=mapper.selectShipmentById(shipmentId);
        try {
            if(mapper.insertAudit(caseId,"SHIPMENT_"+action,CurrentOperator.id(),CurrentOperator.username(),JSON.writeValueAsString(before),JSON.writeValueAsString(after),CurrentOperator.clientIp())!=1)
                throw new BusinessException("AFTER_SALES_AUDIT_FAILED","物流变更历史保存失败");
        }catch(JsonProcessingException e){throw new BusinessException("AFTER_SALES_AUDIT_FAILED","物流变更历史序列化失败");}
        changed(mapper.touchCase(caseId,CurrentOperator.id()));
        return after;
    }
    private static void changed(int rows){if(rows!=1)throw conflict();}
    private static BusinessException state(){return new BusinessException("INVALID_STATUS_TRANSITION","当前案例或物流状态不能执行此操作");}
    private static BusinessException conflict(){return new BusinessException("AFTER_SALES_SHIPMENT_CONFLICT","发运资料已更新，请刷新后重试");}
}
