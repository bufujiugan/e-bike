package com.bike.server.aftersales;

import static com.bike.server.aftersales.AfterSalesAccess.*;
import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.common.PageResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AfterSalesServiceImpl implements AfterSalesService {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Set<String> WARRANTY = Set.of("PENDING", "UNKNOWN", "IN_WARRANTY", "OUT_OF_WARRANTY");
    private final AfterSalesMapper mapper;

    public AfterSalesServiceImpl(AfterSalesMapper mapper) { this.mapper = mapper; }

    @Override public Map<String, List<AfterSalesReferenceOption>> options() {
        requireWrite();
        if (isAgent()) return Map.of("customers", List.of(), "agents", List.of(), "models", mapper.selectModels(requiredAgent()));
        if (!canReadAllCompanies()) return Map.of("customers", List.of(), "agents", mapper.selectCompanies(null, salesScope()).stream().map(c -> new AfterSalesReferenceOption(c.agentId(),c.companyName())).toList(), "models", List.of());
        return Map.of("customers", mapper.selectCustomers(), "agents", mapper.selectAgents(), "models", mapper.selectModels(null));
    }

    @Override public PageResponse<AfterSalesCaseView> list(String keyword, String status, long page, long pageSize) {
        return list(AfterSalesCaseFilter.basic(keyword, status), page, pageSize);
    }

    @Override @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResponse<AfterSalesCaseView> list(AfterSalesCaseFilter request, long page, long pageSize) {
        requireRead();
        var filter = request.normalized();
        checkWarehouseFilters(filter);
        long p = Math.max(1, Math.min(page, Integer.MAX_VALUE)), size = Math.min(100, Math.max(1, pageSize));
        Long scope = isAgent() ? requiredAgent() : null;
        Long sales = salesScope();
        var month = java.time.LocalDate.now().withDayOfMonth(1);
        var rows = mapper.selectFilteredPage(filter, scope, sales, month, (p - 1) * size, size,warehouseOnly());
        rows.forEach(this::redact);
        return new PageResponse<>(rows, p, size, mapper.countFiltered(filter, scope, sales, month,warehouseOnly()));
    }

    @Override public AfterSalesDashboardView dashboard(AfterSalesCaseFilter request) {
        requireRead();
        var filter=request.normalized().withoutStatusAndQueue();checkWarehouseFilters(filter);
        return mapper.selectDashboard(filter, isAgent() ? requiredAgent() : null,
            salesScope(), java.time.LocalDate.now().withDayOfMonth(1),warehouseOnly());
    }

    @Override @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public AfterSalesCaseView get(long id) {
        requireRead();
        return enrich(requireCase(id, false));
    }

    @Override @Transactional public AfterSalesCaseView create(AfterSalesCaseCreateRequest request) {
        requireWrite();
        Long agentId = isAgent() ? requiredAgent() : request.agentId();
        requireAgentScope(mapper, agentId);
        var normalized = validateRequest(request, agentId, null);
        String no = "AS" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        changed(mapper.insertCase(no, normalized, agentId, CurrentOperator.id()));
        long id = insertedId();
        changed(mapper.insertContactSnapshot(id, normalized.contact(), normalized.endCustomerPurchaseDate()));
        var created = requireCase(id, false);
        audit(id, "CASE_CREATED", null, created);
        return enrich(created);
    }

    @Override @Transactional public AfterSalesCaseView update(long id, AfterSalesCaseCreateRequest request) {
        requireWrite();
        var before = requireCase(id, true);
        requireState(before, "DRAFT", "NEEDS_INFO");
        if (request.agentId() != null && !Objects.equals(before.getAgentId(), request.agentId()))
            throw new BusinessException("AFTER_SALES_SCOPE_IMMUTABLE", "已有案例不能变更代理商归属");
        var normalized = validateRequest(request, before.getAgentId(), before);
        changed(mapper.updateCase(id, normalized, CurrentOperator.id()));
        changed(mapper.updateContactSnapshot(id, normalized.contact(), normalized.endCustomerPurchaseDate()));
        return auditedCase(id, "CASE_UPDATED", before);
    }

    @Override @Transactional public AfterSalesCaseView submit(long id) {
        requireWrite();
        var before = requireCase(id, true);
        requireState(before, "DRAFT", "NEEDS_INFO");
        required(before.getFrameSn(), 128, "提交时必须填写车架号");
        required(before.getFaultType(), 64, "提交时必须填写故障类型");
        required(before.getDescription(), 5000, "问题描述不能为空");
        changeStatus(before, "SUBMITTED", null);
        return auditedCase(id, "CASE_SUBMITTED", before);
    }

    @Override @Transactional public AfterSalesCaseView review(long id, AfterSalesReviewRequest request) {
        String action = required(request.action(), 32, "审核动作不能为空").toUpperCase(Locale.ROOT);
        if (action.equals("REQUEST_INFO")) requireInternal("AFTER_SALES_REVIEW", "AFTER_SALES_TECH");
        else requireInternal("AFTER_SALES_REVIEW");
        var before = requireCase(id, true);
        String reason = optional(request.reason(), 1000, "审核说明长度不能超过1000");
        String target;
        switch (action) {
            case "START" -> { requireState(before, "SUBMITTED"); target = "UNDER_REVIEW"; }
            case "APPROVE" -> {
                requireState(before, "UNDER_REVIEW");
                if (normalize(before.getSolution()) == null)
                    throw new BusinessException("AFTER_SALES_SOLUTION_REQUIRED", "审批前须保存技术维修方案");
                new AfterSalesPlans(mapper).approve(id,request.solutionVersionId());
                target = "APPROVED";
            }
            case "REVISE" -> {
                requireState(before,"APPROVED","IN_PROGRESS","WAITING_PARTS","SHIPPED","RESOLVED");
                if(reason==null)throw invalid("重新审核必须说明原因");
                var latest=mapper.selectLatestPlan(id);
                if(!Objects.equals(request.solutionVersionId(),latest==null?null:latest.id()))throw new BusinessException("AFTER_SALES_SOLUTION_CONFLICT","方案已变化，请刷新后重试");
                target="UNDER_REVIEW";
            }
            case "REJECT", "REQUEST_INFO" -> {
                if (reason == null) throw invalid("拒绝或补充资料必须填写原因");
                requireState(before, "SUBMITTED", "UNDER_REVIEW");
                if(action.equals("REJECT") && mapper.countCaseShipments(id)>0)throw new BusinessException("INVALID_STATUS_TRANSITION","已有补发记录的案例不能拒绝终止，请修订方案后重新审批");
                target = action.equals("REJECT") ? "REJECTED" : "NEEDS_INFO";
                new AfterSalesPlans(mapper).invalidate(id);
            }
            default -> throw invalid("审核动作必须是 START、APPROVE、REJECT、REQUEST_INFO 或 REVISE");
        }
        changeStatus(before, target, reason);
        return auditedCase(id, "REVIEW_" + action, before);
    }

    @Override @Transactional public AfterSalesCaseView saveSolution(long id, AfterSalesSolutionRequest request) {
        requireInternal("AFTER_SALES_TECH");
        var before = requireCase(id, true);
        requireState(before, "UNDER_REVIEW");
        var previous=mapper.selectLatestPlan(id);
        var saved=new AfterSalesPlans(mapper).save(before,request);
        audit(id,"SOLUTION_SAVED",previous,saved);
        return enrich(requireCase(id,false));
    }

    @Override @Transactional public AfterSalesCaseView confirmSolution(long id,long versionId) {
        requireInternal("AFTER_SALES_TECH");var c=requireCase(id,true);requireState(c,"UNDER_REVIEW");
        var previous=mapper.selectLatestPlan(id);var confirmed=new AfterSalesPlans(mapper).confirm(id,versionId);
        audit(id,"SOLUTION_CONFIRMED",previous,confirmed);
        return enrich(requireCase(id,false));
    }

    @Override public List<AfterSalesPlanView> solutionVersions(long id) {
        requireCaseContent();requireCase(id,false);return new AfterSalesPlans(mapper).versions(id,canReadInternal());
    }

    @Override @Transactional public AfterSalesCaseView reconcileShipment(long id,long shipmentId,long itemId,String reason) {
        requireInternal("AFTER_SALES_REVIEW");var c=requireCase(id,true);requireState(c,"UNDER_REVIEW");
        String explanation=required(reason,1000,"请说明历史补发核对依据");var previous=mapper.selectShipmentById(shipmentId);
        new AfterSalesPlans(mapper).reconcile(id,shipmentId,itemId);
        audit(id,"SHIPMENT_APPROVAL_RECONCILED",previous,Map.of("shipment",mapper.selectShipmentById(shipmentId),"reason",explanation));
        return enrich(requireCase(id,false));
    }

    @Override @Transactional public AfterSalesCaseView startProcessing(long id) {
        requireInternal("AFTER_SALES_REVIEW", "AFTER_SALES_TECH");
        var before = requireCase(id, true);
        requireState(before, "APPROVED");
        changeStatus(before, "IN_PROGRESS", null);
        return auditedCase(id, "PROCESSING_STARTED", before);
    }

    @Override @Transactional public AfterSalesMessageView addMessage(long id, AfterSalesMessageRequest request) {
        requireCommunicationWrite();
        var c = requireCase(id, true);
        requireState(c, "SUBMITTED", "UNDER_REVIEW", "NEEDS_INFO", "APPROVED", "IN_PROGRESS", "WAITING_PARTS", "SHIPPED", "RESOLVED");
        String visibility = normalize(request.visibility());
        if (visibility == null) visibility = "CUSTOMER";
        if (!Set.of("CUSTOMER", "INTERNAL").contains(visibility)) throw invalid("留言可见范围必须是 CUSTOMER 或 INTERNAL");
        if (visibility.equals("INTERNAL") && !canReadInternal())
            throw new BusinessException("PERMISSION_DENIED", "当前账号不能发送内部留言");
        String body = required(request.body(), 4000, "留言不能为空");
        changed(mapper.insertMessage(id, CurrentOperator.id(), visibility, body));
        var own = mapper.selectMessageById(insertedId());
        if (own == null || !Objects.equals(own.caseId(), id)) throw new BusinessException("AFTER_SALES_UPDATE_FAILED", "留言回读失败");
        changed(mapper.touchCase(id, CurrentOperator.id()));
        audit(id, visibility.equals("INTERNAL") ? "INTERNAL_MESSAGE_ADDED" : "MESSAGE_ADDED", null, own);
        return own;
    }

    @Override public List<AfterSalesMessageView> messages(long id) {
        requireCaseContent(); requireCase(id, false);
        return mapper.selectMessages(id, canReadInternal());
    }

    @Override @Transactional public AfterSalesShipmentView addShipment(long id, AfterSalesShipmentRequest request) {
        requireInternal("AFTER_SALES_LOGISTICS");
        var before = requireCase(id, true);
        requireState(before, "APPROVED", "IN_PROGRESS", "WAITING_PARTS", "SHIPPED");
        String part = required(request.partName(), 255, "补发配件不能为空");
        if (request.quantity() <= 0) throw invalid("补发数量必须大于0");
        String carrier = optional(request.carrier(), 128, "承运商长度不能超过128");
        String tracking = optional(request.trackingNo(), 128, "运单号长度不能超过128");
        if (tracking != null && carrier == null) throw invalid("登记实际发货时必须填写承运商");
        var item=new AfterSalesPlans(mapper).allocate(id,request);
        var normalized = new AfterSalesShipmentRequest(part, request.quantity(), carrier, tracking, request.oldPartReturnRequired(),item.id());
        changed(mapper.insertShipment(id, normalized, CurrentOperator.id()));
        var own = mapper.selectShipmentById(insertedId());
        if (own == null || !Objects.equals(own.getCaseId(), id)) throw new BusinessException("AFTER_SALES_UPDATE_FAILED", "补发记录回读失败");
        if (own.isOldPartReturnRequired()) changed(mapper.insertRequiredReturn(own.getId()));
        changeStatus(before, mapper.countPendingShipments(id) > 0 ? "WAITING_PARTS" : "SHIPPED", null);
        audit(id, tracking == null ? "SHIPMENT_PLANNED" : "SHIPMENT_DISPATCHED", before, Map.of("shipment", own, "case", requireCase(id, false)));
        return own;
    }

    @Override @Transactional public AfterSalesShipmentView dispatchShipment(long id, long shipmentId, AfterSalesDispatchRequest request) {
        requireInternal("AFTER_SALES_LOGISTICS");
        var before = requireCase(id, true);
        requireState(before, "APPROVED", "IN_PROGRESS", "WAITING_PARTS", "SHIPPED");
        var shipment = mapper.selectShipmentById(shipmentId);
        if (shipment == null || !Objects.equals(shipment.getCaseId(), id))
            throw new BusinessException("AFTER_SALES_SHIPMENT_NOT_FOUND", "补发记录不存在");
        if (!"PLANNED".equals(shipment.getStatus())) throw new BusinessException("INVALID_STATUS_TRANSITION", "只有待发货记录可以登记发货");
        new AfterSalesPlans(mapper).requireDispatchApproval(id,shipment);
        var normalized = new AfterSalesDispatchRequest(required(request.carrier(), 128, "承运商不能为空"),
            required(request.trackingNo(), 128, "运单号不能为空"));
        changed(mapper.dispatchShipment(id, shipmentId, normalized));
        var updated = mapper.selectShipmentById(shipmentId);
        changeStatus(before, mapper.countPendingShipments(id) > 0 ? "WAITING_PARTS" : "SHIPPED", null);
        audit(id, "SHIPMENT_DISPATCHED", shipment, updated);
        return updated;
    }

    @Override public List<AfterSalesShipmentView> shipments(long id) {
        requireRead(); requireCase(id, false);
        return mapper.selectShipments(id);
    }

    @Override public List<AfterSalesHistoryView> history(long id) {
        requireRead(); requireCase(id, false);
        return visibleHistory(id);
    }

    @Override @Transactional public AfterSalesCaseView resolve(long id) {
        requireInternal("AFTER_SALES_TECH");
        var before = requireCase(id, true);
        requireState(before, "IN_PROGRESS", "SHIPPED");
        if (mapper.countPendingShipments(id) != 0 || new AfterSalesPlans(mapper).hasUnallocatedParts(id))
            throw new BusinessException("AFTER_SALES_SHIPMENT_PENDING", "尚有待发货配件，不能标记已解决");
        changeStatus(before, "RESOLVED", null);
        return auditedCase(id, "CASE_RESOLVED", before);
    }

    @Override @Transactional public AfterSalesCaseView close(long id) {
        requireWrite();
        var before = requireCase(id, true);
        requireState(before, "RESOLVED");
        if (mapper.countPendingReturns(id) != 0)
            throw new BusinessException("AFTER_SALES_RETURN_PENDING", "尚有未完成的旧件退回，请完成处理或由审核人员记录豁免原因");
        changeStatus(before, "CLOSED", null);
        return auditedCase(id, "CASE_CLOSED", before);
    }

    private AfterSalesCaseCreateRequest validateRequest(AfterSalesCaseCreateRequest r, Long agentId, AfterSalesCaseView existing) {
        if (isAgent() && r.agentId() != null && !Objects.equals(r.agentId(), requiredAgent()))
            throw new BusinessException("FORBIDDEN", "不能提交其他代理商案例");
        if (r.customerId() == null && agentId == null) throw new BusinessException("CUSTOMER_OR_AGENT_REQUIRED", "客户或代理商不能为空");
        // Typed IDs alone are not proof of ownership. A current shipped vehicle is required for
        // a legacy agent/customer link; unlinked vehicle details remain self-reported claims.
        if (isAgent() && r.customerId() != null && r.vehicleId() == null)
            throw new BusinessException("FORBIDDEN", "代理商不能关联未经车辆归属验证的客户");
        if (!isAgent() && !canReadAllCompanies() && r.customerId()!=null && r.vehicleId()==null)
            throw new BusinessException("FORBIDDEN","负责公司范围内的账号不能关联未经车辆归属验证的客户");
        String warranty = normalize(r.warrantyStatus());
        if (warranty == null) warranty = "PENDING";
        if (!WARRANTY.contains(warranty)) throw invalid("保修状态不合法");
        if (isAgent() && !Set.of("PENDING", "UNKNOWN").contains(warranty))
            throw new BusinessException("FORBIDDEN", "代理商不能判定保修责任");
        if (isAgent() && existing != null) warranty = existing.getWarrantyStatus();
        String frame = optional(r.frameSn(), 128, "车架号长度不能超过128");
        String motor = optional(r.motorSn(), 128, "电机序列号长度不能超过128");
        String battery = optional(r.batterySn(), 128, "电池序列号长度不能超过128");
        String display = optional(r.displaySn(), 128, "仪表序列号长度不能超过128");
        Long model = r.productModelId(), customer = r.customerId();
        if (r.vehicleId() != null) {
            var vehicle = mapper.selectVehicleReference(r.vehicleId());
            if (vehicle == null || (isAgent() && !Objects.equals(vehicle.agentId(), requiredAgent())))
                throw new BusinessException(isAgent() ? "FORBIDDEN" : "VEHICLE_NOT_FOUND", "车辆不存在或不属于当前业务范围");
            if ((customer != null && !Objects.equals(customer, vehicle.customerId()))
                || (agentId != null && !Objects.equals(agentId, vehicle.agentId()))
                || (model != null && !Objects.equals(model, vehicle.modelId()))
                || conflicts(frame, vehicle.frameSn()) || conflicts(motor, vehicle.motorSn())
                || conflicts(battery, vehicle.batterySn()) || conflicts(display, vehicle.displaySn()))
                throw new BusinessException("AFTER_SALES_VEHICLE_MISMATCH", "案例客户、车型或序列号与关联车辆不一致");
            customer = vehicle.customerId(); model = vehicle.modelId(); frame = vehicle.frameSn();
            motor = vehicle.motorSn(); battery = vehicle.batterySn(); display = vehicle.displaySn();
        }
        if (agentId != null && mapper.countEnabledAgent(agentId) != 1) throw new BusinessException("AGENT_NOT_FOUND", "代理商不存在或已停用");
        if (customer != null && mapper.countEnabledCustomer(customer) != 1) throw new BusinessException("CUSTOMER_NOT_FOUND", "客户不存在或已停用");
        if (model != null && isAgent() && mapper.countAccessibleModel(model, requiredAgent()) != 1)
            throw new BusinessException("MODEL_NOT_AUTHORIZED", "车型未授权且没有本代理商的已出库车辆");
        if(model != null && !isAgent() && !canReadAllCompanies() && (agentId==null || mapper.countAccessibleModel(model,agentId)!=1))
            throw new BusinessException("MODEL_NOT_AUTHORIZED","车型不在负责公司的授权或出库范围内");
        if (model != null && mapper.countEnabledModel(model) != 1) throw new BusinessException("MODEL_NOT_FOUND", "车型不存在或已停用");
        return new AfterSalesCaseCreateRequest(customer, agentId, r.vehicleId(), frame, model, motor, battery, display,
            r.purchaseDate(), r.failureTime(), optional(r.faultType(), 64, "故障类型长度不能超过64"),
            optional(r.faultPart(), 128, "故障部件长度不能超过128"), optional(r.errorCode(), 128, "故障代码长度不能超过128"),
            required(r.description(), 5000, "问题描述不能为空"), optional(r.attemptedRepair(), 2000, "已尝试维修方法长度不能超过2000"), warranty,
            AfterSalesContactValidation.normalize(r.contact()), AfterSalesContactValidation.date(r.endCustomerPurchaseDate()));
    }

    private AfterSalesCaseView requireCase(long id, boolean lock) {
        var c = lock ? mapper.selectForUpdate(id) : mapper.selectById(id);
        requireCaseScope(mapper, c);
        return c;
    }
    private AfterSalesCaseView enrich(AfterSalesCaseView c) {
        boolean internal = canReadInternal();
        c.setMessages(warehouseOnly()?List.of():mapper.selectMessages(c.getId(), internal));
        c.setShipments(mapper.selectShipments(c.getId()));
        c.setHistory(visibleHistory(c.getId()));
        c.setSolutionVersion(new AfterSalesPlans(mapper).latestView(c.getId(),internal));
        redact(c);
        return c;
    }
    private void redact(AfterSalesCaseView c) {
        if (!canReadInternal()) c.setInternalNote(null);
        if(warehouseOnly()) {
            c.setVehicleId(null);c.setFrameSn(null);c.setProductModelId(null);c.setModelName(null);c.setMotorSn(null);c.setBatterySn(null);c.setDisplaySn(null);
            c.setPurchaseDate(null);c.setEndCustomerPurchaseDate(null);c.setFailureTime(null);c.setFaultType(null);c.setFaultPart(null);c.setErrorCode(null);c.setDescription(null);c.setAttemptedRepair(null);
            c.setWarrantyStatus(null);c.setTechnicalJudgment(null);c.setSolution(null);c.setRejectionReason(null);c.setContactEmail(null);c.setAssigneeId(null);c.setAssigneeName(null);
            c.setCreatedBy(null);c.setUpdatedBy(null);c.setMessages(List.of());
        }
    }
    private List<AfterSalesHistoryView> visibleHistory(long id) {
        var history=mapper.selectHistory(id,canReadInternal());
        return warehouseOnly()?history.stream().filter(h->h.action().startsWith("SHIPMENT_")||h.action().startsWith("RETURN_")).toList():history;
    }
    private static void checkWarehouseFilters(AfterSalesCaseFilter filter) {
        if(warehouseOnly() && (filter.frameSn()!=null||filter.modelName()!=null||filter.faultType()!=null||filter.assigneeName()!=null))throw new BusinessException("PERMISSION_DENIED","仓库岗位不能按车辆、故障或售后负责人检索案例");
    }
    private AfterSalesCaseView auditedCase(long id, String action, AfterSalesCaseView before) {
        var after = requireCase(id, false);
        audit(id, action, before, after);
        return enrich(after);
    }
    private void audit(long id, String action, Object before, Object after) {
        try {
            if (mapper.insertAudit(id, action, CurrentOperator.id(), CurrentOperator.username(),
                before == null ? null : JSON.writeValueAsString(before), after == null ? null : JSON.writeValueAsString(after),
                CurrentOperator.clientIp()) != 1) throw new BusinessException("AFTER_SALES_AUDIT_FAILED", "售后变更历史保存失败");
        } catch (JsonProcessingException error) { throw new BusinessException("AFTER_SALES_AUDIT_FAILED", "售后变更历史序列化失败"); }
    }
    private void changeStatus(AfterSalesCaseView before, String target, String reason) {
        changed(mapper.updateStatus(before.getId(), before.getStatus(), target, reason, CurrentOperator.id()));
    }
    private long insertedId() {
        Long id = mapper.selectInsertedId();
        if (id == null || id <= 0) throw new BusinessException("AFTER_SALES_UPDATE_FAILED", "售后记录回读失败");
        return id;
    }
    private static void changed(int count) { if (count != 1) throw new BusinessException("AFTER_SALES_UPDATE_FAILED", "售后记录已变化，请刷新后重试"); }
    private static void requireState(AfterSalesCaseView c, String... allowed) {
        if (!List.of(allowed).contains(c.getStatus())) throw new BusinessException("INVALID_STATUS_TRANSITION", "当前案例状态不允许该操作");
    }
    private static boolean conflicts(String requested, String actual) { return requested != null && !Objects.equals(requested, actual); }
    private static String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String optional(String value, int max, String message) {
        String normalized = normalize(value);
        if (normalized != null && normalized.length() > max) throw invalid(message);
        return normalized;
    }
    private static String required(String value, int max, String message) {
        String normalized = optional(value, max, message);
        if (normalized == null) throw invalid(message);
        return normalized;
    }
    private static BusinessException invalid(String message) { return new BusinessException("INVALID_PARAMETER", message); }
}
