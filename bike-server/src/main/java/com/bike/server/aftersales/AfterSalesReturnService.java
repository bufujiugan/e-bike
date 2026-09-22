package com.bike.server.aftersales;

import static com.bike.server.aftersales.AfterSalesAccess.*;
import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AfterSalesReturnService {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final AfterSalesMapper mapper;
    public AfterSalesReturnService(AfterSalesMapper mapper) { this.mapper = mapper; }

    public List<AfterSalesReturnView> list(long caseId) {
        requireRead(); requireCaseScope(mapper, mapper.selectById(caseId));
        return mapper.selectReturns(caseId);
    }

    @Transactional public AfterSalesReturnView act(long caseId, long shipmentId, AfterSalesReturnRequest request) {
        requireRead();
        String action = text(request.action(),32,true).toUpperCase(Locale.ROOT);
        switch (action) {
            case "SEND" -> { if (isAgent()) requireWrite(); else requireInternal("AFTER_SALES_REVIEW", "AFTER_SALES_LOGISTICS"); }
            case "REQUEST", "RECEIVE" -> requireInternal("AFTER_SALES_LOGISTICS");
            case "INSPECT" -> requireInternal("AFTER_SALES_TECH");
            case "SCRAP", "COMPLETE", "WAIVE" -> requireInternal("AFTER_SALES_REVIEW");
            default -> throw invalid("旧件操作不合法");
        }
        var c = mapper.selectForUpdate(caseId);
        requireCaseScope(mapper,c);
        if (Set.of("DRAFT","SUBMITTED","UNDER_REVIEW","NEEDS_INFO","CLOSED","REJECTED").contains(c.getStatus()))
            throw new BusinessException("INVALID_STATUS_TRANSITION","当前案例状态不能处理旧件退回");
        var before = mapper.selectReturn(shipmentId);
        if (before == null || before.caseId()!=caseId) throw new BusinessException("AFTER_SALES_RETURN_NOT_FOUND","需要退回的旧件不存在");
        if (request.revision()!=before.revision()) throw new BusinessException("AFTER_SALES_RETURN_CONFLICT","旧件处理记录已更新，请刷新后重试");
        String target;
        switch (action) {
            case "REQUEST" -> { state(before,"REQUIRED","WAITING_CUSTOMER"); target="WAITING_CUSTOMER"; }
            case "SEND" -> { state(before,"WAITING_CUSTOMER"); target="IN_TRANSIT"; }
            case "RECEIVE" -> { state(before,"IN_TRANSIT"); target="RECEIVED"; }
            case "INSPECT" -> { state(before,"RECEIVED"); target="INSPECTED"; }
            case "SCRAP" -> { state(before,"INSPECTED"); target="SCRAPPED"; }
            case "COMPLETE" -> { state(before,"INSPECTED","SCRAPPED"); target="COMPLETED"; }
            case "WAIVE" -> { state(before,"REQUIRED","WAITING_CUSTOMER"); target="WAIVED"; }
            default -> throw invalid("旧件操作不合法");
        }
        var normalized = new AfterSalesReturnRequest(before.revision(),action,
            text(request.warehouseName(),128,action.equals("REQUEST")),text(request.recipient(),100,action.equals("REQUEST")),
            text(request.phone(),64,action.equals("REQUEST")),text(request.addressLine(),500,action.equals("REQUEST")),
            text(request.postalCode(),32,action.equals("REQUEST")),text(request.carrier(),128,action.equals("SEND")),
            text(request.trackingNo(),128,action.equals("SEND")),text(request.inspectionResult(),2000,action.equals("INSPECT")),
            text(request.reason(),1000,Set.of("SCRAP","COMPLETE","WAIVE").contains(action)));
        if (mapper.updateReturn(caseId,shipmentId,target,normalized,CurrentOperator.id())!=1)
            throw new BusinessException("AFTER_SALES_RETURN_CONFLICT","旧件处理记录已更新，请刷新后重试");
        if (action.equals("RECEIVE") && mapper.markReturnReceived(caseId,shipmentId)!=1)
            throw new BusinessException("AFTER_SALES_UPDATE_FAILED","旧件收件记录保存失败");
        var after = mapper.selectReturn(shipmentId);
        try {
            if (mapper.insertAudit(caseId,"RETURN_"+action,CurrentOperator.id(),CurrentOperator.username(),
                JSON.writeValueAsString(before),JSON.writeValueAsString(after),CurrentOperator.clientIp())!=1)
                throw new BusinessException("AFTER_SALES_AUDIT_FAILED","旧件处理历史保存失败");
        } catch (JsonProcessingException error) { throw new BusinessException("AFTER_SALES_AUDIT_FAILED","旧件处理历史序列化失败"); }
        if (mapper.touchCase(caseId,CurrentOperator.id())!=1) throw new BusinessException("AFTER_SALES_UPDATE_FAILED","案例更新时间保存失败");
        return after;
    }

    private static void state(AfterSalesReturnView record, String... allowed) {
        if (!Set.of(allowed).contains(record.status())) throw new BusinessException("INVALID_STATUS_TRANSITION","当前旧件状态不能执行此操作");
    }
    private static String text(String value,int max,boolean required) {
        String s=value==null?null:value.trim();
        if(s!=null && s.isEmpty())s=null;
        if((required && s==null) || (s!=null && (s.length()>max || s.chars().anyMatch(ch->Character.isISOControl(ch)&&ch!='\n'&&ch!='\r')))) throw invalid("请填写完整、有效的旧件处理资料");
        return s;
    }
    private static BusinessException invalid(String message) { return new BusinessException("INVALID_PARAMETER",message); }
}
