package com.bike.server.pi;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.common.OperationLogMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PiWorkflowServiceImpl implements PiWorkflowService {
    private final PiMapper pis;
    private final PiWorkflowMapper mapper;
    private final OperationLogMapper logs;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.common.CustomerScopeAccess customerScope;

    public PiWorkflowServiceImpl(PiMapper pis, PiWorkflowMapper mapper, OperationLogMapper logs, ObjectMapper json) {
        this.pis = pis;
        this.mapper = mapper;
        this.logs = logs;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public PiWorkflowView get(long piId) {
        CurrentOperator.requireWebAccess();
        CurrentOperator.requirePermission("PI_READ");
        PiView pi = requirePi(piId, false);
        List<PiWorkflowStepRecord> persisted = mapper.selectSteps(piId);
        if (persisted.isEmpty() && pi.getPreviousRevisionId() != null) {
            persisted = mapper.selectSteps(pi.getPreviousRevisionId());
        }
        List<PiWorkflowStepView> steps = effectiveSteps(pi, persisted);
        return CurrentOperator.hasRole("WEB_AGENT") ? agentView(steps) : view(steps, updates(pi));
    }

    @Override
    @Transactional
    public PiWorkflowView complete(long piId, String code, PiWorkflowRequest request) {
        CurrentOperator.requireInternalWebAccess();
        CurrentOperator.requirePermission("PI_WRITE");
        PiWorkflowStepCode step = PiWorkflowStepCode.parse(code);
        if (step == null) throw new BusinessException("PI_WORKFLOW_STEP_INVALID", "流程步骤不存在");
        if (step == PiWorkflowStepCode.PRODUCTION_FOLLOW_UP) {
            throw new BusinessException("PI_WORKFLOW_PRODUCTION_UPDATE_REQUIRED", "请通过生产跟进入口记录进度并确认是否完成");
        }
        PiView pi = requirePi(piId, true);
        if ("CANCELLED".equals(pi.getStatus()) || "SUPERSEDED".equals(pi.getStatus())) {
            throw new BusinessException("PI_WORKFLOW_CLOSED", "已关闭的PI不能推进流程");
        }

        mapper.initialize(piId);
        if (pi.getPreviousRevisionId() != null) mapper.inheritCompleted(piId, pi.getPreviousRevisionId());
        List<PiWorkflowStepRecord> persisted = mapper.selectStepsForUpdate(piId);
        List<PiWorkflowStepView> effective = effectiveSteps(pi, persisted);

        // 仅把已经有权威业务事实的前置步骤落库，不用其他步骤的近似状态代替业务确认。
        for (PiWorkflowStepView item : effective) {
            if (item.order() >= step.order() || !"COMPLETED".equals(item.status())) continue;
            PiWorkflowStepRecord row = find(persisted, item.order());
            if (row != null && !"COMPLETED".equals(row.status())) {
                mapper.complete(piId, item.order(), CurrentOperator.id(), item.note(), null);
            }
        }

        persisted = mapper.selectStepsForUpdate(piId);
        effective = effectiveSteps(pi, persisted);
        PiWorkflowStepView current = effective.stream().filter(item -> item.order() == step.order()).findFirst().orElseThrow();
        if ("COMPLETED".equals(current.status())) return view(effective, updates(pi));
        for (PiWorkflowStepView item : effective) {
            if (item.order() < step.order() && !"COMPLETED".equals(item.status())) {
                throw new BusinessException("PI_WORKFLOW_ORDER_INVALID", "请先完成前置流程：" + item.name());
            }
        }

        validateBusinessGate(pi, step, request);
        String note = request == null ? null : normalize(request.note());
        String detailJson = serialize(details(request));
        if (mapper.complete(piId, step.order(), CurrentOperator.id(), note, detailJson) != 1) {
            throw new BusinessException("PI_WORKFLOW_CONFLICT", "流程步骤已被其他操作更新，请刷新后重试");
        }
        if (logs.insert("PI_WORKFLOW", Long.toString(piId), "完成" + step.stepName(), CurrentOperator.id(),
                CurrentOperator.username(), "{\"status\":\"PENDING\"}", "{\"status\":\"COMPLETED\"}",
                CurrentOperator.clientIp(), note) != 1) {
            throw new BusinessException("PI_WORKFLOW_AUDIT_FAILED", "流程变更审计失败");
        }
        return view(effectiveSteps(pi, mapper.selectSteps(piId)), updates(pi));
    }

    @Override
    @Transactional
    public PiWorkflowView recordProductionUpdate(long piId, PiWorkflowRequest request) {
        CurrentOperator.requireInternalWebAccess();
        CurrentOperator.requirePermission("PI_WRITE");
        PiView pi = requirePi(piId, true);
        if ("CANCELLED".equals(pi.getStatus()) || "SUPERSEDED".equals(pi.getStatus())) {
            throw new BusinessException("PI_WORKFLOW_CLOSED", "已关闭的PI不能记录生产进度");
        }
        mapper.initialize(piId);
        if (pi.getPreviousRevisionId() != null) mapper.inheritCompleted(piId, pi.getPreviousRevisionId());
        List<PiWorkflowStepRecord> persisted = mapper.selectStepsForUpdate(piId);
        List<PiWorkflowStepView> effective = effectiveSteps(pi, persisted);
        for (PiWorkflowStepView item : effective) {
            if (item.order() >= PiWorkflowStepCode.PRODUCTION_FOLLOW_UP.order()
                    || !"COMPLETED".equals(item.status())) continue;
            PiWorkflowStepRecord row = find(persisted, item.order());
            if (row != null && !"COMPLETED".equals(row.status())) {
                mapper.complete(piId, item.order(), CurrentOperator.id(), item.note(), null);
            }
        }
        persisted = mapper.selectStepsForUpdate(piId);
        effective = effectiveSteps(pi, persisted);
        PiWorkflowStepView production = effective.stream()
                .filter(item -> item.code().equals(PiWorkflowStepCode.PRODUCTION_FOLLOW_UP.name())).findFirst().orElseThrow();
        if ("COMPLETED".equals(production.status())) {
            throw new BusinessException("PI_WORKFLOW_PRODUCTION_COMPLETED", "生产跟进已完成，不能继续追加记录");
        }
        for (PiWorkflowStepView item : effective) {
            if (item.order() < production.order() && !"COMPLETED".equals(item.status())) {
                throw new BusinessException("PI_WORKFLOW_ORDER_INVALID", "请先完成前置流程：" + item.name());
            }
        }
        validateBusinessGate(pi, PiWorkflowStepCode.PRODUCTION_FOLLOW_UP, request);
        PiWorkflowDetails details = details(request);
        String detailJson = serialize(details);
        String note = request == null ? null : normalize(request.note());
        if (mapper.insertUpdate(piId, PiWorkflowStepCode.PRODUCTION_FOLLOW_UP.name(), detailJson, note,
                CurrentOperator.id()) != 1) {
            throw new BusinessException("PI_WORKFLOW_UPDATE_FAILED", "生产跟进记录保存失败");
        }
        if (Boolean.TRUE.equals(request.productionCompleted())
                && mapper.complete(piId, production.order(), CurrentOperator.id(), note, detailJson) != 1) {
            throw new BusinessException("PI_WORKFLOW_CONFLICT", "流程步骤已被其他操作更新，请刷新后重试");
        }
        String action = Boolean.TRUE.equals(request.productionCompleted()) ? "完成工厂生产跟进" : "记录工厂生产跟进";
        if (logs.insert("PI_WORKFLOW", Long.toString(piId), action, CurrentOperator.id(),
                CurrentOperator.username(), null, "{\"productionCompleted\":" + Boolean.TRUE.equals(request.productionCompleted()) + "}",
                CurrentOperator.clientIp(), note) != 1) {
            throw new BusinessException("PI_WORKFLOW_AUDIT_FAILED", "生产跟进审计失败");
        }
        return view(effectiveSteps(pi, mapper.selectSteps(piId)), updates(pi));
    }

    private PiView requirePi(long id, boolean lock) {
        PiView pi = lock ? pis.selectForUpdate(id) : pis.selectById(id);
        if (pi == null) throw new BusinessException("PI_NOT_FOUND", "PI不存在");
        customerScope.requireCustomer(pi.getCustomerId());
        if (CurrentOperator.hasRole("WEB_AGENT") && (!Objects.equals(CurrentOperator.agentId(), pi.getAgentId())
                || !published(pi.getStatus()))) throw new BusinessException("FORBIDDEN", "无权访问该PI");
        return pi;
    }

    private void validateBusinessGate(PiView pi, PiWorkflowStepCode step, PiWorkflowRequest request) {
        if (step == PiWorkflowStepCode.PAYMENT_CONFIRMED && "UNPAID".equals(pi.getPaymentStatus())) {
            throw new BusinessException("PI_WORKFLOW_PAYMENT_REQUIRED", "确认付款前请先登记实际到账");
        }
        if (step == PiWorkflowStepCode.DOCUMENTS_READY) {
            if (!published(pi.getStatus())) throw new BusinessException("PI_WORKFLOW_PI_CONFIRM_REQUIRED", "请先确认并发布PI");
            if (request == null || blank(request.contractNo()) || !Boolean.TRUE.equals(request.contractReady())
                    || !Boolean.TRUE.equals(request.paymentTableReady()) || !Boolean.TRUE.equals(request.productionTableReady())) {
                throw new BusinessException("PI_WORKFLOW_DOCUMENT_REQUIRED", "请填写合同号并确认合同、收付款表和生产表均已建立");
            }
        }
        if (step == PiWorkflowStepCode.PRODUCTION_FOLLOW_UP
                && (request == null || blank(request.productionStatus()))) {
            throw new BusinessException("PI_WORKFLOW_PRODUCTION_REQUIRED", "请填写工厂反馈的生产进度");
        }
        if (step == PiWorkflowStepCode.FORWARDER_SELECTED
                && (request == null || blank(request.forwarderName()) || request.quotedAmount() == null
                || blank(request.quoteCurrency()))) {
            throw new BusinessException("PI_WORKFLOW_FORWARDER_REQUIRED", "请填写已选货代、报价和币种");
        }
        if (step == PiWorkflowStepCode.DOMESTIC_EXPRESS_BOOKED
                && (request == null || blank(request.domesticCarrier()) || request.domesticPrice() == null
                || blank(request.domesticTrackingNo()))) {
            throw new BusinessException("PI_WORKFLOW_DOMESTIC_EXPRESS_REQUIRED", "请填写国内快递公司、价格和单号");
        }
        if (step == PiWorkflowStepCode.TRACKING_RECORDED && !hasInternationalTracking(pi)) {
            throw new BusinessException("PI_WORKFLOW_TRACKING_REQUIRED", "请先登记已发货的国际运输承运商和单号");
        }
        if (step == PiWorkflowStepCode.CUSTOMER_RECEIVED) {
            if (!hasInternationalTracking(pi)) {
                throw new BusinessException("PI_WORKFLOW_SHIPMENT_REQUIRED", "请先登记已发货的国际运输信息");
            }
            if (request == null || request.receivedDate() == null) {
                throw new BusinessException("PI_WORKFLOW_RECEIPT_REQUIRED", "请填写客户确认收货日期");
            }
            if (request.receivedDate().isAfter(LocalDate.now())) {
                throw new BusinessException("PI_WORKFLOW_RECEIPT_DATE_INVALID", "客户收货日期不能晚于今天");
            }
        }
    }

    private List<PiWorkflowStepView> effectiveSteps(PiView pi, List<PiWorkflowStepRecord> persisted) {
        boolean paymentConfirmed = pi.getPaymentStatus() != null && !"UNPAID".equals(pi.getPaymentStatus());
        boolean trackingRecorded = hasInternationalTracking(pi);
        List<PiWorkflowStepView> result = new ArrayList<>();
        for (PiWorkflowStepCode step : PiWorkflowStepCode.values()) {
            PiWorkflowStepRecord row = find(persisted, step.order());
            boolean stored = row != null && "COMPLETED".equals(row.status());
            boolean factual = step == PiWorkflowStepCode.PI_CREATED
                    || step == PiWorkflowStepCode.PAYMENT_CONFIRMED && paymentConfirmed
                    || step == PiWorkflowStepCode.TRACKING_RECORDED && trackingRecorded;
            String note = row == null ? null : row.note();
            if (!stored && factual) note = switch (step) {
                case PI_CREATED -> "PI已建立";
                case PAYMENT_CONFIRMED -> "已由到账记录确认";
                case TRACKING_RECORDED -> "已由国际运输记录确认";
                default -> note;
            };
            result.add(new PiWorkflowStepView(step.order(), step.name(), step.stepName(),
                    stored || factual ? "COMPLETED" : "PENDING", row == null ? null : row.completedBy(),
                    row == null ? null : row.completedAt(), note, parseDetails(row == null ? null : row.detailJson())));
        }
        return result;
    }

    private boolean hasInternationalTracking(PiView pi) {
        return pis.selectLogistics(pi.getId()).stream().anyMatch(item -> "SHIPPED".equals(item.getStatus())
                && !blank(item.getCarrier()) && !blank(item.getTrackingNo()));
    }

    private PiWorkflowView agentView(List<PiWorkflowStepView> all) {
        List<PiWorkflowStepView> visible = all.stream().filter(item -> item.code().equals(PiWorkflowStepCode.PI_CREATED.name())
                || item.code().equals(PiWorkflowStepCode.TRACKING_RECORDED.name())
                || item.code().equals(PiWorkflowStepCode.CUSTOMER_RECEIVED.name()))
                .map(item -> new PiWorkflowStepView(item.order(), item.code(), item.name(), item.status(), null,
                        item.completedAt(), item.note(), item.code().equals(PiWorkflowStepCode.CUSTOMER_RECEIVED.name()) ? item.details() : null))
                .toList();
        return view(visible, List.of());
    }

    private PiWorkflowView view(List<PiWorkflowStepView> steps, List<PiWorkflowUpdateView> updates) {
        int completed = (int) steps.stream().filter(item -> "COMPLETED".equals(item.status())).count();
        PiWorkflowStepView next = steps.stream().filter(item -> !"COMPLETED".equals(item.status())).findFirst().orElse(null);
        String stage = next == null ? "已完成" : next.name();
        String current = next == null ? PiWorkflowStepCode.CUSTOMER_RECEIVED.name() : next.code();
        return new PiWorkflowView(stage, current, completed, steps.size(), next == null ? null : next.name(), steps, updates);
    }

    private List<PiWorkflowUpdateView> updates(PiView pi) {
        List<PiWorkflowUpdateRecord> records = new ArrayList<>(mapper.selectUpdates(pi.getId()));
        if (pi.getPreviousRevisionId() != null) records.addAll(mapper.selectUpdates(pi.getPreviousRevisionId()));
        return records.stream().map(item -> new PiWorkflowUpdateView(item.id(), item.stepCode(),
                parseDetails(item.detailJson()), item.note(), item.createdBy(), item.createdAt())).toList();
    }

    private PiWorkflowDetails details(PiWorkflowRequest request) {
        if (request == null) return null;
        PiWorkflowDetails details = new PiWorkflowDetails(normalize(request.contractNo()), request.contractReady(),
                request.paymentTableReady(), request.productionTableReady(), normalize(request.productionStatus()),
                request.expectedCompletionDate(), normalize(request.materialStatus()), normalize(request.forwarderName()),
                request.quotedAmount(), upper(request.quoteCurrency()), normalize(request.domesticCarrier()),
                request.domesticPrice(), normalize(request.domesticTrackingNo()), request.receivedDate());
        return details.equals(new PiWorkflowDetails(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null)) ? null : details;
    }

    private String serialize(PiWorkflowDetails value) {
        if (value == null) return null;
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new BusinessException("PI_WORKFLOW_DETAIL_INVALID", "流程业务明细保存失败"); }
    }

    private PiWorkflowDetails parseDetails(String value) {
        if (blank(value)) return null;
        try { return json.readValue(value, PiWorkflowDetails.class); }
        catch (JsonProcessingException error) { throw new BusinessException("PI_WORKFLOW_DETAIL_INVALID", "流程业务明细读取失败"); }
    }

    private static PiWorkflowStepRecord find(List<PiWorkflowStepRecord> steps, int order) {
        return steps.stream().filter(item -> item.order() == order).findFirst().orElse(null);
    }

    private static boolean published(String status) { return "CONFIRMED".equals(status) || "RELEASED".equals(status); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String normalize(String value) { return blank(value) ? null : value.trim(); }
    private static String upper(String value) { String normalized = normalize(value); return normalized == null ? null : normalized.toUpperCase(java.util.Locale.ROOT); }
}
