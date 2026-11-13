package com.bike.server.pi;

import com.bike.server.common.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PiWorkflowServiceImpl implements PiWorkflowService {
    @org.springframework.beans.factory.annotation.Autowired private PiFulfillmentProfileService profiles;
    @org.springframework.beans.factory.annotation.Autowired private PiFulfillmentProfileMapper profileMapper;
    private final PiMapper pis;
    private final PiWorkflowMapper mapper;
    private final OperationLogMapper logs;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired private CustomerScopeAccess customerScope;
    @org.springframework.beans.factory.annotation.Autowired private PiBusinessService business;
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.production.ProductionGuard inventoryGuard;
    public PiWorkflowServiceImpl(PiMapper pis,PiWorkflowMapper mapper,OperationLogMapper logs,ObjectMapper json) {
        this.pis=pis;this.mapper=mapper;this.logs=logs;this.json=json;
    }
    @Override @Transactional(readOnly=true)
    public PiWorkflowView get(long piId) {
        CurrentOperator.requireWebAccess();CurrentOperator.requirePermission("PI_READ");
        var pi=requirePi(piId,false);var rows=records(pi);var steps=effectiveSteps(pi,rows);
        if(CurrentOperator.hasRole("WEB_AGENT")) {
            steps=steps.stream().filter(s->Set.of("PI_CREATED","TRACKING_RECORDED","CUSTOMER_RECEIVED").contains(s.code()))
                .map(s->new PiWorkflowStepView(s.order(),s.code(),s.name(),s.status(),null,s.completedAt(),null,
                    "CUSTOMER_RECEIVED".equals(s.code())?s.details():null)).toList();
            return view(steps,List.of());
        }
        return view(steps,updates(pi));
    }
    @Override @Transactional
    public PiWorkflowView complete(long piId,String code,PiWorkflowRequest request) {
        CurrentOperator.requireInternalWebAccess();
        var step=PiWorkflowStepCode.parse(code);
        if(step==PiWorkflowStepCode.CREDIT_APPROVED)CurrentOperator.requirePermission("PI_RELEASE");
        else if(step==PiWorkflowStepCode.INVENTORY_RESERVED)CurrentOperator.requireAnyPermission("PI_RELEASE","PI_PAYMENT_CONFIRM");
        else CurrentOperator.requirePermission("PI_WRITE");
        if(step==null)fail("PI_WORKFLOW_STEP_INVALID","流程步骤不存在");
        if(step==PiWorkflowStepCode.PRODUCTION_FOLLOW_UP)fail("PI_WORKFLOW_PRODUCTION_UPDATE_REQUIRED","请从生产跟进入口记录进度");
        if(inventoryGuard!=null)inventoryGuard.lock();
        var pi=requirePi(piId,true);requireOpen(pi);initialize(pi);
        var rows=mapper.selectStepsForUpdate(piId);var effective=effectiveSteps(pi,rows);
        var profile=profile(pi);
        var current=effective.stream().filter(s->s.code().equals(step.name())).findFirst().orElseThrow(()->new BusinessException("PI_WORKFLOW_STEP_NOT_APPLICABLE","本单配置不适用该步骤"));
        if(step==PiWorkflowStepCode.CREDIT_APPROVED)CurrentOperator.requirePermission("PI_RELEASE");
        if("COMPLETED".equals(current.status()))return view(effective,updates(pi));
        for(var prior:effective) {
            if(profile!=null) {
                if(requiredBefore(step,profile.rules()).contains(prior.code())&&!"COMPLETED".equals(prior.status()))fail("PI_WORKFLOW_ORDER_INVALID","请先完成："+prior.name());
                continue;
            }
            if(prior.code().equals(step.name()))break;
            // 国内购销合同允许付款前办理，也允许付款后补齐，但真实出库始终校验。
            if(step==PiWorkflowStepCode.PURCHASE_CONTRACT_READY&&!Set.of("PI_CREATED","DOCUMENTS_READY").contains(prior.code()))continue;
            if(!"COMPLETED".equals(prior.status()))fail("PI_WORKFLOW_ORDER_INVALID","请先完成："+prior.name());
        }
        if(step==PiWorkflowStepCode.INVENTORY_RESERVED) {
            CurrentOperator.requireAnyPermission("PI_RELEASE","PI_PAYMENT_CONFIRM");
            business.requireDocuments(piId);
            if(!business.reserveAfterPayment(pi,pi.getPaidAmount()))fail("PI_WAREHOUSE_STOCK_SHORTAGE","指定仓库可用库存不足或履约SKU不完整，请核对后重试；到账记录已保留");
            return view(effectiveSteps(pi,mapper.selectSteps(piId)),updates(pi));
        }
        validateBusinessGate(pi,step,request,rows);
        if(profile!=null)validateConfiguredStep(pi,step,request,profile,effective);
        if(Set.of(PiWorkflowStepCode.PI_CREATED,PiWorkflowStepCode.PAYMENT_CONFIRMED,PiWorkflowStepCode.INVENTORY_RESERVED,PiWorkflowStepCode.STOCK_DISPATCHED,PiWorkflowStepCode.PAYMENT_SETTLED).contains(step))
            fail("PI_WORKFLOW_FACT_REQUIRED","该步骤由真实业务记录自动确认，请使用对应业务入口");
        if(mapper.complete(piId,step.order(),CurrentOperator.id(),request==null?null:normalize(request.note()),serialize(details(request)))!=1)
            fail("PI_WORKFLOW_CONFLICT","流程已变化，请刷新重试");
        audit(piId,"完成"+step.stepName(),request==null?null:request.note());
        return view(effectiveSteps(pi,mapper.selectSteps(piId)),updates(pi));
    }
    @Override @Transactional
    public PiWorkflowView recordProductionUpdate(long piId,PiWorkflowRequest request) {
        CurrentOperator.requireInternalWebAccess();CurrentOperator.requirePermission("PI_WRITE");
        if(inventoryGuard!=null)inventoryGuard.lock();
        var pi=requirePi(piId,true);requireOpen(pi);initialize(pi);
        if(request==null||blank(request.productionStatus()))fail("PI_WORKFLOW_PRODUCTION_REQUIRED","请填写生产进度");
        if(mapper.insertUpdate(piId,"PRODUCTION_FOLLOW_UP",serialize(details(request)),normalize(request.note()),CurrentOperator.id())!=1)
            fail("PI_WORKFLOW_UPDATE_FAILED","生产跟进保存失败");
        audit(piId,"记录生产跟进",request.note());
        return view(effectiveSteps(pi,mapper.selectSteps(piId)),updates(pi));
    }
    private void initialize(PiView pi) {
        mapper.initialize(pi.getId());
        Long previousId = pi.getPreviousRevisionId();
        for (int depth = 0; previousId != null && depth < 100; depth++) {
            mapper.inheritCompleted(pi.getId(), previousId);
            var previous = pis.selectById(previousId);
            previousId = previous == null ? null : previous.getPreviousRevisionId();
        }
    }
    private List<PiWorkflowStepRecord> records(PiView pi) {
        var result=new LinkedHashMap<String,PiWorkflowStepRecord>();
        for(int depth=0;pi!=null&&depth<100;depth++) {
            for (var row : mapper.selectSteps(pi.getId())) {
                if (depth > 0 && !Set.of("PURCHASE_CONTRACT_READY", "FORWARDER_SELECTED").contains(row.code())) continue;
                var existing = result.get(row.code());
                if (existing == null || !"COMPLETED".equals(existing.status()) && "COMPLETED".equals(row.status()))
                    result.put(row.code(), row);
            }
            pi=pi.getPreviousRevisionId()==null?null:pis.selectById(pi.getPreviousRevisionId());
        }
        return List.copyOf(result.values());
    }
    private List<PiWorkflowStepView> effectiveSteps(PiView pi,List<PiWorkflowStepRecord> rows) {
        var result=new ArrayList<PiWorkflowStepView>();
        var d=business==null?null:business.document(pi.getId());
        boolean overseas=d!=null&&d.business()!=null&&Boolean.FALSE.equals(d.business().domesticShipment());
        var profile=profile(pi);
        for(var step:stepsFor(profile)) {
            if(step==PiWorkflowStepCode.PRODUCTION_FOLLOW_UP)continue;
            var row=rows.stream().filter(s->step.name().equals(s.code())).findFirst().orElse(null);
            boolean stored=row!=null&&"COMPLETED".equals(row.status());
            boolean completed=switch(step) {
                case PI_CREATED -> true;
                case DOCUMENTS_READY -> stored && (profile!=null&&!profile.rules().salesContractRequired()?parse(row.detailJson())!=null&&Boolean.TRUE.equals(parse(row.detailJson()).paymentTableReady()):validDocuments(parse(row.detailJson())));
                case PAYMENT_CONFIRMED -> paymentMet(pi);
                case INVENTORY_RESERVED -> business!=null&&business.hasReservedStock(pi);
                case PURCHASE_CONTRACT_READY -> profile==null?overseas||stored&&validPurchase(parse(row.detailJson())):stored&&parse(row.detailJson())!=null&&!blank(parse(row.detailJson()).purchaseContractNo())&&!blank(parse(row.detailJson()).purchaseContractParty());
                case PAYMENT_SETTLED -> pi.getPaidAmount()!=null&&pi.getTotalAmount()!=null&&pi.getPaidAmount().compareTo(pi.getTotalAmount())>=0;
                case CASE_CLOSED -> stored&&result.stream().allMatch(s->"COMPLETED".equals(s.status()));
                case STOCK_DISPATCHED -> business!=null&&business.allDispatched(pi.getId());
                case FORWARDER_SELECTED -> stored && validForwarder(parse(row.detailJson()));
                case TRACKING_RECORDED -> stored&&business!=null&&business.allTracked(pi.getId());
                case CUSTOMER_RECEIVED -> stored && parse(row.detailJson()) != null
                    && parse(row.detailJson()).receivedDate() != null && business != null && business.allTracked(pi.getId());
                default -> stored;
            };
            String note=profile==null&&step==PiWorkflowStepCode.PURCHASE_CONTRACT_READY&&overseas?"非国内出货，不适用国内购销合同":row==null?null:row.note();
            result.add(new PiWorkflowStepView(result.size()+1,step.name(),profile!=null&&step==PiWorkflowStepCode.CUSTOMER_RECEIVED?"确认全部货物送达":profile!=null&&step==PiWorkflowStepCode.FORWARDER_SELECTED?(profile.rules().customerPickup()?"确认客户货代提货安排":"确认我方运输安排"):step.stepName(),completed?"COMPLETED":"PENDING",
                row==null?null:row.completedBy(),row==null?null:row.completedAt(),note,row==null?null:parse(row.detailJson())));
        }
        return result;
    }
    private void validateBusinessGate(PiView pi,PiWorkflowStepCode step,PiWorkflowRequest r,List<PiWorkflowStepRecord> rows) {
        var profile=profile(pi);
        if(step==PiWorkflowStepCode.DOCUMENTS_READY) {
            if(!Set.of("CONFIRMED","RELEASED").contains(pi.getStatus()))fail("PI_WORKFLOW_PI_CONFIRM_REQUIRED","请先确认并发布PI");
            if(r==null||((profile==null||profile.rules().salesContractRequired())&&(blank(r.contractNo())||!Boolean.TRUE.equals(r.contractReady())))||!Boolean.TRUE.equals(r.paymentTableReady()))
                fail("PI_WORKFLOW_DOCUMENT_REQUIRED","请填写销售合同号，并确认PI、合同及收付款表已核对");
        }
        if(step==PiWorkflowStepCode.PURCHASE_CONTRACT_READY&&(profile==null?!validPurchase(details(r)):r==null||blank(r.purchaseContractNo())||blank(r.purchaseContractParty())))
            fail("PI_PURCHASE_CONTRACT_REQUIRED","请填写SAVA或银三环购销合同号");
        if(step==PiWorkflowStepCode.FORWARDER_SELECTED) {
            if(r==null||!Set.of("CUSTOMER","SELLER").contains(Objects.toString(r.forwarderResponsibility(),""))
                ||blank(r.forwarderName())||blank(r.forwarderAddress())||blank(r.forwarderContact()))
                fail("PI_WORKFLOW_FORWARDER_REQUIRED","请选择货代责任方并填写货代名称、仓库地址和收货联系人");
        }
        if(profile!=null&&step==PiWorkflowStepCode.FORWARDER_SELECTED&&!Objects.equals(profile.rules().customerPickup()?"CUSTOMER":"SELLER",r.forwarderResponsibility()))fail("PI_DELIVERY_MISMATCH","提货责任方须与本单配置一致");
        boolean seller=profile!=null?!profile.rules().customerPickup():!"CUSTOMER".equals(forwarderResponsibility(rows));
        if(step==PiWorkflowStepCode.DOMESTIC_EXPRESS_BOOKED&&seller&&
                (r==null||blank(r.domesticCarrier())||r.domesticPrice()==null||blank(r.domesticTrackingNo())))
            fail("PI_WORKFLOW_DOMESTIC_EXPRESS_REQUIRED","我方送仓请填写承运商、单号及运费");
        if(step==PiWorkflowStepCode.DOMESTIC_EXPRESS_BOOKED&&!seller&&(r==null||blank(r.note())))
            fail("PI_WORKFLOW_TRANSPORT_REQUIRED","请记录客户指定货代的送仓安排或实际运输信息");
        if(step==PiWorkflowStepCode.WAREHOUSE_ARRIVED) {
            if(r==null||r.warehouseArrivalDate()==null||r.warehouseArrivalDate().isAfter(LocalDate.now()))
                fail("PI_WORKFLOW_ARRIVAL_REQUIRED","请填写实际到达货代仓库日期");
            if(blank(r.domesticTrackingNo()))
                fail("PI_WORKFLOW_ARRIVAL_TRACKING_REQUIRED","请填写送达货代仓库的物流单号");
        }
        if(step==PiWorkflowStepCode.TRACKING_RECORDED) {
            if(business==null||!business.allTracked(pi.getId()))fail("PI_WORKFLOW_TRACKING_REQUIRED","请为全部实际出库批次登记发运信息");
            if(seller&&(r==null||blank(r.internationalTrackingNo())||r.internationalPrice()==null||blank(r.internationalCurrency())))
                fail("PI_WORKFLOW_TRACKING_REQUIRED","我方货代须填写发货号、实际费用及币种");
            if(!seller&&(r==null||blank(r.note())))fail("PI_WORKFLOW_TRACKING_REQUIRED","请登记客户货代可获取的发货信息；未知信息请说明");
        }
        if(step==PiWorkflowStepCode.CUSTOMER_RECEIVED) {
            if(business==null||!business.allTracked(pi.getId()))fail("PI_WORKFLOW_SHIPMENT_REQUIRED","仍有未出库或未登记发运的货物，不能完成订单");
            if(r==null||r.receivedDate()==null||r.receivedDate().isAfter(LocalDate.now()))fail("PI_WORKFLOW_RECEIPT_REQUIRED","请填写全部货物确认签收的实际日期");
        }
    }
    private String forwarderResponsibility(List<PiWorkflowStepRecord> rows) {
        return rows.stream().filter(s->"FORWARDER_SELECTED".equals(s.code())).map(s->parse(s.detailJson()))
                .filter(Objects::nonNull).map(PiWorkflowDetails::forwarderResponsibility).filter(Objects::nonNull).findFirst().orElse(null);
    }
    private boolean validForwarder(PiWorkflowDetails d) {
        return d != null && Set.of("CUSTOMER", "SELLER").contains(Objects.toString(d.forwarderResponsibility(), ""))
                && !blank(d.forwarderName()) && !blank(d.forwarderAddress()) && !blank(d.forwarderContact());
    }
    private boolean validDocuments(PiWorkflowDetails d) {
        return d != null && !blank(d.contractNo()) && Boolean.TRUE.equals(d.contractReady())
                && Boolean.TRUE.equals(d.paymentTableReady());
    }
    private boolean validPurchase(PiWorkflowDetails d){return d!=null&&!blank(d.purchaseContractNo())&&Set.of("SAVA","YINSANHUAN").contains(Objects.toString(d.purchaseContractParty(),""));}
    private boolean paymentMet(PiView pi){return pi.getPaidAmount()!=null&&pi.getPaidAmount().signum()>0&&pi.getDepositRequiredAmount()!=null&&pi.getPaidAmount().compareTo(pi.getDepositRequiredAmount())>=0;}
    private PiView requirePi(long id,boolean lock) {
        var pi=lock?pis.selectForUpdate(id):pis.selectById(id);if(pi==null)fail("PI_NOT_FOUND","PI不存在");
        customerScope.requireCustomer(pi.getCustomerId());
        if(CurrentOperator.hasRole("WEB_AGENT")&&(!Objects.equals(CurrentOperator.agentId(),pi.getAgentId())||!Set.of("CONFIRMED","RELEASED").contains(pi.getStatus())))fail("FORBIDDEN","无权访问该PI");
        return pi;
    }
    private void requireOpen(PiView pi){if(Set.of("CANCELLED","SUPERSEDED").contains(pi.getStatus()))fail("PI_WORKFLOW_CLOSED","已关闭的PI不能推进流程");}
    private PiWorkflowView view(List<PiWorkflowStepView> steps,List<PiWorkflowUpdateView> updates) {
        var next=steps.stream().filter(s->!"COMPLETED".equals(s.status())).findFirst().orElse(null);
        return new PiWorkflowView(next==null?"已完成":next.name(),next==null?(steps.isEmpty()?"CUSTOMER_RECEIVED":steps.getLast().code()):next.code(),
                (int)steps.stream().filter(s->"COMPLETED".equals(s.status())).count(),steps.size(),next==null?null:next.name(),steps,updates);
    }
    private List<PiWorkflowUpdateView> updates(PiView pi){return mapper.selectUpdates(pi.getId()).stream().map(u->new PiWorkflowUpdateView(u.id(),u.stepCode(),parse(u.detailJson()),u.note(),u.createdBy(),u.createdAt())).toList();}
    private PiWorkflowDetails details(PiWorkflowRequest r) {
        if(r==null)return null;
        return new PiWorkflowDetails(normalize(r.contractNo()),r.contractReady(),r.paymentTableReady(),r.productionTableReady(),normalize(r.productionStatus()),r.expectedCompletionDate(),normalize(r.materialStatus()),normalize(r.forwarderName()),r.quotedAmount(),upper(r.quoteCurrency()),normalize(r.domesticCarrier()),r.domesticPrice(),normalize(r.domesticTrackingNo()),r.receivedDate(),normalize(r.purchaseContractNo()),r.purchaseContractParty(),r.forwarderResponsibility(),normalize(r.forwarderAddress()),normalize(r.forwarderContact()),r.warehouseArrivalDate(),normalize(r.arrivalProof()),normalize(r.internationalTrackingNo()),r.internationalPrice(),upper(r.internationalCurrency()));
    }
    private PiFulfillmentProfile profile(PiView pi){return profiles==null||business==null?null:profiles.resolveProfile(business.document(pi.getId()));}
    static List<PiWorkflowStepCode> stepsFor(PiFulfillmentProfile profile) {
        if(profile==null)return Arrays.stream(PiWorkflowStepCode.values()).filter(s->s.order()<=12&&s!=PiWorkflowStepCode.PRODUCTION_FOLLOW_UP).toList();
        var r=profile.rules();var steps=new ArrayList<PiWorkflowStepCode>();
        steps.add(PiWorkflowStepCode.PI_CREATED);steps.add(PiWorkflowStepCode.DOCUMENTS_READY);
        steps.add(r.credit()?PiWorkflowStepCode.CREDIT_APPROVED:PiWorkflowStepCode.PAYMENT_CONFIRMED);
        steps.add(PiWorkflowStepCode.INVENTORY_RESERVED);
        if(r.purchaseContractRequired())steps.add(PiWorkflowStepCode.PURCHASE_CONTRACT_READY);
        if(r.dispatchSheetRequired())steps.add(PiWorkflowStepCode.DISPATCH_SHEET_SENT);
        if(r.preparationRequired())steps.add(PiWorkflowStepCode.GOODS_PREPARED);
        steps.add(PiWorkflowStepCode.FORWARDER_SELECTED);steps.add(PiWorkflowStepCode.STOCK_DISPATCHED);
        if(r.viaForwarderWarehouse()){steps.add(PiWorkflowStepCode.DOMESTIC_EXPRESS_BOOKED);steps.add(PiWorkflowStepCode.WAREHOUSE_ARRIVED);}
        steps.add(PiWorkflowStepCode.TRACKING_RECORDED);steps.add(PiWorkflowStepCode.CUSTOMER_RECEIVED);
        if(r.requiredDocuments().contains("CI"))steps.add(PiWorkflowStepCode.CI_SENT);
        if(r.requiredDocuments().contains("RD")){steps.add(PiWorkflowStepCode.RD_SENT);steps.add(PiWorkflowStepCode.RD_SIGNED);}
        if(r.settlementRequired())steps.add(PiWorkflowStepCode.PAYMENT_SETTLED);
        if(r.requiredDocuments().contains("CMR"))steps.add(PiWorkflowStepCode.CMR_COLLECTED);
        steps.add(PiWorkflowStepCode.CASE_CLOSED);return steps;
    }
    private Set<String> requiredBefore(PiWorkflowStepCode step,PiFulfillmentRules r) {
        return switch(step) {
            case PI_CREATED,DOCUMENTS_READY,PAYMENT_CONFIRMED,PAYMENT_SETTLED -> Set.of();
            case CREDIT_APPROVED,PURCHASE_CONTRACT_READY -> Set.of("DOCUMENTS_READY");
            case INVENTORY_RESERVED -> Set.of("DOCUMENTS_READY",r.credit()?"CREDIT_APPROVED":"PAYMENT_CONFIRMED");
            case DISPATCH_SHEET_SENT -> Set.of("INVENTORY_RESERVED");
            case GOODS_PREPARED -> Set.of(r.dispatchSheetRequired()?"DISPATCH_SHEET_SENT":"INVENTORY_RESERVED");
            case FORWARDER_SELECTED -> Set.of("DOCUMENTS_READY");
            case DOMESTIC_EXPRESS_BOOKED,WAREHOUSE_ARRIVED,TRACKING_RECORDED -> Set.of("STOCK_DISPATCHED");
            case CUSTOMER_RECEIVED -> r.viaForwarderWarehouse()?Set.of("TRACKING_RECORDED","WAREHOUSE_ARRIVED"):Set.of("TRACKING_RECORDED");
            case CI_SENT,RD_SENT -> Set.of("CUSTOMER_RECEIVED");
            case RD_SIGNED -> Set.of("RD_SENT");
            case CMR_COLLECTED -> Set.of("STOCK_DISPATCHED");
            default -> Set.of();
        };
    }
    private void validateConfiguredStep(PiView pi,PiWorkflowStepCode step,PiWorkflowRequest request,PiFulfillmentProfile profile,List<PiWorkflowStepView> effective) {
        if(step==PiWorkflowStepCode.CASE_CLOSED) {
            if(effective.stream().anyMatch(s->!s.code().equals("CASE_CLOSED")&&!"COMPLETED".equals(s.status())))fail("PI_CLOSE_NOT_READY","交付、应收款或必需单据尚未完成，不能结案");
        }
        if(step==PiWorkflowStepCode.CREDIT_APPROVED) {
            var due=business.document(pi.getId()).business().paymentDueDate();
            if(due==null||due.isBefore(LocalDate.now()))fail("PI_CREDIT_DUE_INVALID","后付款授权须明确未过期的付款到期日");
        }
        if(Set.of(PiWorkflowStepCode.CREDIT_APPROVED,PiWorkflowStepCode.DISPATCH_SHEET_SENT,PiWorkflowStepCode.GOODS_PREPARED,PiWorkflowStepCode.CI_SENT,PiWorkflowStepCode.RD_SENT,PiWorkflowStepCode.RD_SIGNED,PiWorkflowStepCode.CMR_COLLECTED,PiWorkflowStepCode.CASE_CLOSED).contains(step)) {
            if(request==null||blank(request.note()))fail("PI_WORKFLOW_EVIDENCE_REQUIRED","请填写实际办理情况、接收方或授权依据");
        }
        if(Set.of(PiWorkflowStepCode.DISPATCH_SHEET_SENT,PiWorkflowStepCode.CI_SENT,PiWorkflowStepCode.RD_SENT,PiWorkflowStepCode.RD_SIGNED,PiWorkflowStepCode.CMR_COLLECTED).contains(step)) {
            if(blank(request.arrivalProof())||profileMapper.countDocumentEvidence(pi.getId(),request.arrivalProof())!=1)fail("PI_DOCUMENT_EVIDENCE_REQUIRED","请先上传本PI的业务单据附件并选择对应文件");
            if(request.receivedDate()==null||request.receivedDate().isAfter(LocalDate.now()))fail("PI_DOCUMENT_DATE_REQUIRED","请填写实际发送或回收日期");
        }
    }
    private String serialize(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("流程记录序列化失败",e);}}
    private PiWorkflowDetails parse(String value){try{return value==null?null:json.readValue(value,PiWorkflowDetails.class);}catch(JsonProcessingException e){throw new IllegalStateException("流程记录读取失败",e);}}
    private void audit(long id,String action,String note){if(logs.insert("PI_WORKFLOW",Long.toString(id),action,CurrentOperator.id(),CurrentOperator.username(),null,null,CurrentOperator.clientIp(),normalize(note))!=1)fail("PI_WORKFLOW_AUDIT_FAILED","流程审计失败");}
    private static String normalize(String s){return blank(s)?null:s.trim();}
    private static String upper(String s){return blank(s)?null:s.trim().toUpperCase(Locale.ROOT);}
    private static boolean blank(String s){return s==null||s.isBlank();}
    private static void fail(String code,String message){throw new BusinessException(code,message);}
}
