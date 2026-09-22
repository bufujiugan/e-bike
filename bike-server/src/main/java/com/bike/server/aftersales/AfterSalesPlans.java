package com.bike.server.aftersales;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** All writes are called inside the case service transaction after acquiring its case row lock. */
final class AfterSalesPlans {
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private final AfterSalesMapper mapper;
    AfterSalesPlans(AfterSalesMapper mapper){this.mapper=mapper;}

    AfterSalesPlanRecord save(AfterSalesCaseView c,AfterSalesSolutionRequest request) {
        var previous=mapper.selectLatestPlan(c.getId());
        if(!Objects.equals(request.previousVersionId(),previous==null?null:previous.id()))throw conflict();
        var normalized=normalize(request);
        String document;
        try{document=JSON.writeValueAsString(normalized);}catch(JsonProcessingException e){throw invalid("维修方案不能序列化");}
        changed(mapper.insertPlan(c.getId(),previous==null?1:previous.revision()+1,document,CurrentOperator.id()));
        Long id=mapper.selectInsertedId();if(id==null)throw conflict();
        for(var part:normalized.parts())changed(mapper.insertPlanItem(id,part));
        if(previous!=null && Set.of("DRAFT","CONFIRMED").contains(previous.status()))changed(mapper.transitionPlan(previous.id(),previous.status(),"SUPERSEDED",null));
        changed(mapper.saveSolution(c.getId(),normalized,CurrentOperator.id()));
        return mapper.selectLatestPlan(c.getId());
    }

    AfterSalesPlanRecord confirm(long caseId,long versionId) {
        var plan=expected(caseId,versionId,"DRAFT");var doc=read(plan);
        if(!Set.of("IN_WARRANTY","OUT_OF_WARRANTY").contains(Objects.toString(doc.warrantyStatus(),"")) || text(doc.technicalJudgment(),4000,false)==null)
            throw invalid("技术确认前须填写故障判断并明确保修责任");
        changed(mapper.transitionPlan(plan.id(),"DRAFT","CONFIRMED",CurrentOperator.id()));
        return mapper.selectLatestPlan(caseId);
    }

    void approve(long caseId,Long versionId) {
        var plan=expected(caseId,versionId,"CONFIRMED");
        if(mapper.countUnlinkedLegacyShipments(caseId)>0)throw new BusinessException("AFTER_SALES_LEGACY_RECONCILIATION_REQUIRED","请先逐项核对并关联历史补发记录，不能推测旧配件审批");
        if(mapper.countUnrepresentedAllocations(caseId,plan.id())>0)throw new BusinessException("AFTER_SALES_PART_ALLOCATION_CONFLICT","新方案必须包含已计划或已发的配件编号");
        for(var item:mapper.selectPlanItems(plan.id()))if(item.allocatedQty()>item.quantity())
            throw new BusinessException("AFTER_SALES_PART_ALLOCATION_CONFLICT","批准数量不能少于同配件已经计划或已发的累计数量");
        changed(mapper.transitionPlan(plan.id(),"CONFIRMED","APPROVED",CurrentOperator.id()));
        changed(mapper.applyPlanWarranty(caseId,read(plan).warrantyStatus()));
    }

    void invalidate(long caseId) {
        var plan=mapper.selectLatestPlan(caseId);
        if(plan!=null && Set.of("DRAFT","CONFIRMED").contains(plan.status()))changed(mapper.transitionPlan(plan.id(),plan.status(),"SUPERSEDED",null));
    }

    AfterSalesPlanItem allocate(long caseId,AfterSalesShipmentRequest request) {
        var plan=mapper.selectLatestPlan(caseId);
        if(plan==null || !"APPROVED".equals(plan.status()) || request.solutionItemId()==null)
            throw new BusinessException("AFTER_SALES_APPROVED_PART_REQUIRED","补发必须选择当前已审批方案中的配件");
        var item=mapper.selectPlanItem(request.solutionItemId());
        if(item==null || item.versionId()!=plan.id())throw new BusinessException("AFTER_SALES_APPROVED_PART_REQUIRED","配件不属于当前已审批方案");
        if(!item.partName().equals(request.partName().trim()) || item.returnRequired()!=request.oldPartReturnRequired())
            throw invalid("配件名称及旧件退回要求必须与批准方案一致");
        if(request.quantity()>item.quantity()-item.allocatedQty())throw new BusinessException("AFTER_SALES_APPROVED_QUANTITY_EXCEEDED","补发数量超过当前配件剩余批准数量");
        return item;
    }

    void requireDispatchApproval(long caseId,AfterSalesShipmentView shipment) {
        var plan=mapper.selectLatestPlan(caseId);
        if(shipment.getSolutionItemId()==null)throw new BusinessException("AFTER_SALES_LEGACY_RECONCILIATION_REQUIRED","历史补发须先关联经审批的配件条目");
        if(plan==null || !"APPROVED".equals(plan.status()))throw new BusinessException("AFTER_SALES_APPROVED_PART_REQUIRED","当前方案尚未审批，不能实际发货");
        var original=mapper.selectPlanItem(shipment.getSolutionItemId());
        var current=original==null?null:mapper.selectPlanItems(plan.id()).stream().filter(i->i.partCode().equals(original.partCode())).findFirst().orElse(null);
        if(current==null || current.allocatedQty()>current.quantity())throw new BusinessException("AFTER_SALES_PART_ALLOCATION_CONFLICT","当前审批未覆盖该补发记录");
    }

    void reconcile(long caseId,long shipmentId,long itemId) {
        var shipment=mapper.selectShipmentById(shipmentId);var latest=mapper.selectLatestPlan(caseId);var item=mapper.selectPlanItem(itemId);
        if(shipment==null || !Objects.equals(shipment.getCaseId(),caseId))throw new BusinessException("AFTER_SALES_SHIPMENT_NOT_FOUND","补发记录不存在");
        if(!"LEGACY_UNLINKED".equals(shipment.getApprovalSource()) || latest==null || !"DRAFT".equals(latest.status()) || item==null || item.versionId()!=latest.id())
            throw invalid("仅可把待核对的历史补发关联到当前草稿中的明确配件");
        if(item.returnRequired()!=shipment.isOldPartReturnRequired() || item.quantity()-item.allocatedQty()<shipment.getQuantity())throw invalid("历史补发数量或退回要求与待核对条目不符");
        changed(mapper.reconcileShipment(caseId,shipmentId,itemId));
    }

    boolean hasUnallocatedParts(long caseId) {
        var latest=mapper.selectLatestPlan(caseId);
        return latest!=null && "APPROVED".equals(latest.status()) && mapper.selectPlanItems(latest.id()).stream().anyMatch(i->i.quantity()>i.allocatedQty());
    }

    AfterSalesPlanView latestView(long caseId,boolean internal) {var row=mapper.selectLatestPlan(caseId);return row==null?null:view(row,internal);}
    List<AfterSalesPlanView> versions(long caseId,boolean internal) {return mapper.selectPlanVersions(caseId).stream().map(p->view(p,internal)).toList();}
    private AfterSalesPlanView view(AfterSalesPlanRecord row,boolean internal) {
        var d=read(row);
        if(AfterSalesAccess.warehouseOnly())return new AfterSalesPlanView(row.id(),row.revision(),row.status(),
            new AfterSalesSolutionRequest(null,null,null,null,null,null,null,null,null,null,List.of()),
            "APPROVED".equals(row.status())?mapper.selectPlanItems(row.id()).stream().map(p->new AfterSalesPlanItem(p.id(),p.versionId(),p.partCode(),p.partName(),p.quantity(),null,p.returnRequired(),p.allocatedQty())).toList():List.of(),row.createdAt(),row.confirmedAt(),row.approvedAt());
        if(!internal)d=new AfterSalesSolutionRequest(d.technicalJudgment(),d.solution(),null,d.previousVersionId(),d.faultCause(),d.customerActions(),d.repairNotes(),d.warrantyStatus(),d.currency(),d.chargeReason(),d.parts());
        return new AfterSalesPlanView(row.id(),row.revision(),row.status(),d,mapper.selectPlanItems(row.id()),row.createdAt(),row.confirmedAt(),row.approvedAt());
    }
    private AfterSalesPlanRecord expected(long caseId,Long id,String status) {
        var latest=mapper.selectLatestPlan(caseId);
        if(latest==null)throw new BusinessException("AFTER_SALES_SOLUTION_REQUIRED","请先保存技术维修方案");
        if(id==null || id!=latest.id())throw conflict();
        if(!status.equals(latest.status()))throw new BusinessException("INVALID_STATUS_TRANSITION","技术方案状态不允许此操作，请刷新并核对技术确认记录");
        return latest;
    }
    static AfterSalesSolutionRequest read(AfterSalesPlanRecord row) {
        try{return JSON.readValue(row.documentJson(),AfterSalesSolutionRequest.class);}catch(JsonProcessingException e){throw new BusinessException("AFTER_SALES_SOLUTION_INVALID","历史方案数据无法读取");}
    }
    private static AfterSalesSolutionRequest normalize(AfterSalesSolutionRequest r) {
        String warranty=text(r.warrantyStatus(),32,false);if(warranty==null)warranty="PENDING";
        if(!Set.of("PENDING","UNKNOWN","IN_WARRANTY","OUT_OF_WARRANTY").contains(warranty))throw invalid("保修责任不合法");
        String currency=text(r.currency(),3,true).toUpperCase(Locale.ROOT);
        try{if(Currency.getInstance(currency).getDefaultFractionDigits()!=2)throw invalid("当前收费仅支持两位小数的币种");}catch(IllegalArgumentException e){throw invalid("收费币种不合法");}
        var parts=new ArrayList<AfterSalesSolutionRequest.Part>();var codes=new HashSet<String>();BigDecimal total=BigDecimal.ZERO;
        if(r.parts()==null || r.parts().size()>100)throw invalid("请明确填写配件清单，无需配件时提交空清单");
        for(var p:r.parts()) {
            if(p==null)throw invalid("配件条目不能为空");String code=text(p.partCode(),128,true).toUpperCase(Locale.ROOT);
            if(!codes.add(code))throw invalid("同一方案配件编号不能重复");
            if(p.quantity()<=0 || p.quantity()>1000000 || p.unitCharge()==null || p.unitCharge().signum()<0)throw invalid("配件数量或收费金额不合法");
            BigDecimal price;try{price=p.unitCharge().setScale(2,RoundingMode.UNNECESSARY);}catch(ArithmeticException e){throw invalid("收费金额不能超过两位小数");}
            total=total.add(price.multiply(BigDecimal.valueOf(p.quantity())));if(total.compareTo(new BigDecimal("9999999999999999.99"))>0)throw invalid("收费总额超出有效范围");
            parts.add(new AfterSalesSolutionRequest.Part(code,text(p.partName(),255,true),p.quantity(),price,p.returnRequired()));
        }
        return new AfterSalesSolutionRequest(text(r.technicalJudgment(),4000,false),text(r.solution(),4000,true),text(r.internalNote(),4000,false),
            r.previousVersionId(),text(r.faultCause(),2000,false),text(r.customerActions(),4000,false),text(r.repairNotes(),2000,false),warranty,currency,text(r.chargeReason(),1000,total.signum()>0),List.copyOf(parts));
    }
    private static String text(String s,int max,boolean required) {s=s==null?null:s.trim();if(s!=null&&s.isEmpty())s=null;if((required&&s==null)||(s!=null&&s.length()>max))throw invalid("请填写完整且长度有效的技术方案资料");return s;}
    private static void changed(int count){if(count!=1)throw conflict();}
    private static BusinessException conflict(){return new BusinessException("AFTER_SALES_SOLUTION_CONFLICT","方案已变化，请刷新后核对当前版本");}
    private static BusinessException invalid(String message){return new BusinessException("INVALID_PARAMETER",message);}
}
