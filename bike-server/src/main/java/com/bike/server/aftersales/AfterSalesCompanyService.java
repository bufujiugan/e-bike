package com.bike.server.aftersales;

import static com.bike.server.aftersales.AfterSalesAccess.*;
import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AfterSalesCompanyService {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final AfterSalesMapper mapper;
    public AfterSalesCompanyService(AfterSalesMapper mapper) { this.mapper = mapper; }
    public List<AfterSalesCompanyView> list() {
        requireCompanyContent(); return mapper.selectCompanies(isAgent() ? requiredAgent() : null, salesScope());
    }
    public AfterSalesCompanyView get(long agentId) {
        requireCompanyContent(); requireAgentScope(mapper,agentId);
        var row=mapper.selectCompany(agentId); if(row==null) throw new BusinessException("AGENT_NOT_FOUND","公司不存在或已停用"); return row;
    }
    @Transactional public AfterSalesCompanyView save(long agentId,AfterSalesContact contact) {
        manage(); requireAgentScope(mapper,agentId); lock(agentId);
        if(contact==null) throw invalid("公司联系资料不能为空");
        var normalized=AfterSalesContactValidation.normalize(contact); var before=mapper.selectCompany(agentId);
        mapper.upsertCompany(agentId,normalized,CurrentOperator.id());
        var after=mapper.selectCompany(agentId);
        if(after==null || !normalized.equals(new AfterSalesContact(after.contactName(),after.contactPhone(),after.contactEmail(),after.countryCode(),after.region(),after.city(),after.addressLine(),after.postalCode()))) throw updateError();
        companyAudit(agentId,"COMPANY_CONTACT_UPDATED",before,after); return after;
    }
    public Map<String,List<AfterSalesReferenceOption>> staffOptions() {
        requireInternal("AFTER_SALES_COMPANY_MANAGE","AFTER_SALES_ASSIGN");
        return Map.of("salesUsers", mapper.selectStaffOptions(false), "assignees",mapper.selectStaffOptions(true));
    }
    public List<AfterSalesReferenceOption> salespeople(long agentId) { manage(); requireAgentScope(mapper,agentId); return mapper.selectSalespeople(agentId); }
    public List<AfterSalesMemberView> members(long agentId) { manage(); requireAgentScope(mapper,agentId); return mapper.selectCompanyMembers(agentId); }
    public List<AfterSalesHistoryView> history(long agentId) { manage(); requireAgentScope(mapper,agentId); return mapper.selectCompanyHistory(agentId); }
    @Transactional public List<AfterSalesReferenceOption> setSalespeople(long agentId,List<Long> userIds) {
        manage(); requireAgentScope(mapper,agentId); lock(agentId);
        if(userIds==null || userIds.size()>100 || userIds.stream().anyMatch(id->id==null||id<=0) || new HashSet<>(userIds).size()!=userIds.size()) throw invalid("销售人员列表不合法");
        var eligible=mapper.selectStaffOptions(false).stream().map(AfterSalesReferenceOption::id).collect(java.util.stream.Collectors.toSet());
        if(!eligible.containsAll(userIds)) throw invalid("只能分配启用且有售后读取权限的内部销售账号");
        var before=mapper.selectSalespeople(agentId); mapper.disableSalespeople(agentId,CurrentOperator.id());
        for(long id:userIds) mapper.upsertSalesperson(agentId,id,CurrentOperator.id());
        var after=mapper.selectSalespeople(agentId);
        if(!new HashSet<>(userIds).equals(after.stream().map(AfterSalesReferenceOption::id).collect(java.util.stream.Collectors.toSet()))) throw updateError();
        companyAudit(agentId,"COMPANY_SALES_ASSIGNED",before,after); return after;
    }
    @Transactional public AfterSalesCaseView assign(long caseId,Long assigneeId) {
        requireCaseContent();
        requireInternal("AFTER_SALES_ASSIGN");
        var before=mapper.selectForUpdate(caseId); requireCaseScope(mapper,before);
        if(Set.of("CLOSED","REJECTED").contains(before.getStatus())) throw new BusinessException("INVALID_STATUS_TRANSITION","终态案例不能重新派单");
        String name=null;
        if(assigneeId!=null) name=mapper.selectStaffOptions(true).stream().filter(user->Objects.equals(user.id(),assigneeId)).findFirst()
            .orElseThrow(()->invalid("负责人必须是启用且有售后处理权限的内部员工")).name();
        if(assigneeId!=null && mapper.countAssigneeScope(assigneeId,before.getAgentId())!=1) throw invalid("负责人没有当前公司的售后访问范围，请先配置范围");
        if(Objects.equals(before.getAssigneeId(),assigneeId)) { if(!canReadInternal()) before.setInternalNote(null); return before; }
        if(mapper.assignCase(caseId,assigneeId,CurrentOperator.id())!=1 || mapper.snapshotAssignee(caseId,name)!=1) throw new BusinessException("AFTER_SALES_UPDATE_FAILED","派单保存失败");
        var after=mapper.selectById(caseId);
        if(mapper.insertAudit(caseId,"CASE_ASSIGNED",CurrentOperator.id(),CurrentOperator.username(),json(Map.of("assignee",before.getAssigneeId()==null?"":before.getAssigneeId())),json(Map.of("assignee",assigneeId==null?"":assigneeId)),CurrentOperator.clientIp())!=1) throw auditError();
        if(!canReadInternal()) after.setInternalNote(null); return after;
    }
    private void lock(long agentId) { if(mapper.lockAgent(agentId)==null) throw new BusinessException("AGENT_NOT_FOUND","公司不存在或已停用"); }
    private static void manage() { requireInternal("AFTER_SALES_COMPANY_MANAGE"); }
    private void companyAudit(long id,String action,Object before,Object after) {
        if(mapper.insertCompanyAudit(id,action,CurrentOperator.id(),CurrentOperator.username(),json(before),json(after),CurrentOperator.clientIp())!=1) throw auditError();
    }
    private static String json(Object value) { try{return JSON.writeValueAsString(value);} catch(JsonProcessingException e){throw auditError();} }
    private static BusinessException auditError(){return new BusinessException("AFTER_SALES_AUDIT_FAILED","售后管理操作审计失败");}
    private static BusinessException updateError(){return new BusinessException("AFTER_SALES_UPDATE_FAILED","售后管理资料保存失败");}
    private static BusinessException invalid(String message){return new BusinessException("INVALID_PARAMETER",message);}
}
