package com.bike.server.order;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class OrderContractServiceImpl implements OrderContractService {
    private static final Logger LOG = LoggerFactory.getLogger(OrderContractServiceImpl.class);
    private final OrderService orders;
    private final OrderContractMapper mapper;
    private final OrderContractWorkbook workbook;
    private final Validator validator;

    public OrderContractServiceImpl(OrderService orders, OrderContractMapper mapper,
            OrderContractWorkbook workbook, Validator validator) {
        this.orders = orders;
        this.mapper = mapper;
        this.workbook = workbook;
        this.validator = validator;
    }

    @Override
    public Preparation prepareContract(long orderId) {
        SalesOrderView order = authorizedOrder(orderId);
        boolean hasLinkedPi = mapper.existsPiForOrder(orderId);
        String reason = unavailableReason(order, hasLinkedPi);
        if (reason != null) return new Preparation(false, reason, null, hasLinkedPi);
        var buyer = new OrderContractRequest.Party(order.getCustomerName(), "", "", "", "", "");
        var seller = new OrderContractRequest.Party("", "", "", "", "", "");
        return new Preparation(true, null, new OrderContractRequest("", LocalDate.now(), order.getUpdatedAt(), order.getModelId(), order.getOrderQty(),
                buyer, seller, "", "电助力自行车", "", null, null, null,
                "交货前付款", "", "", workbook.defaultClauses()), hasLinkedPi);
    }

    @Override
    public byte[] generateContract(long orderId, OrderContractRequest request) {
        SalesOrderView order = authorizedOrder(orderId);
        String reason = unavailableReason(order, mapper.existsPiForOrder(orderId));
        if (reason != null) throw new BusinessException("ORDER_CONTRACT_UNAVAILABLE", reason);
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new BusinessException("ORDER_CONTRACT_INVALID", "请补全合同必填项，单价最多保留两位小数");
        }
        if (!Objects.equals(order.getUpdatedAt(), request.orderUpdatedAt())
                || !Objects.equals(order.getModelId(), request.modelId()) || order.getOrderQty() != request.orderQty()) {
            throw new BusinessException("ORDER_CONTRACT_STALE", "订单已发生变化，请刷新订单后重新核对合同");
        }
        if (!Objects.equals(order.getCustomerName(), request.buyer().name())) {
            throw new BusinessException("ORDER_CONTRACT_BUYER_INVALID", "合同需方必须与订单客户一致，请刷新订单后重试");
        }
        if (request.unitPrice().multiply(java.math.BigDecimal.valueOf(order.getOrderQty()))
                .compareTo(new java.math.BigDecimal("999999999999.99")) > 0) {
            throw new BusinessException("ORDER_CONTRACT_AMOUNT_INVALID", "合同金额超过 Excel 可精确表示的范围，请拆分订单");
        }
        byte[] result = workbook.render(order, request);
        LOG.info("生成内销购销合同 orderId={} userId={}", orderId, CurrentOperator.id());
        return result;
    }

    private SalesOrderView authorizedOrder(long orderId) {
        CurrentOperator.requireInternalWebAccess();
        CurrentOperator.requirePermission("ORDER_READ");
        CurrentOperator.requirePermission("ORDER_WRITE");
        // 复用订单服务的客户与代理商范围校验，不能只凭订单 ID 导出。
        return orders.get(orderId);
    }

    private String unavailableReason(SalesOrderView order, boolean hasLinkedPi) {
        // 是否内销由经办人在转单、建单或订单办理时明确选择，不能根据有无 PI 推断。
        if (order.getSalesType() == null) return "请先选择本订单的销售类型；选择“内销 · 购销合同”后才能生成购销合同";
        if (!SalesType.DOMESTIC.equals(order.getSalesType())) {
            return "本订单为「" + SalesType.label(order.getSalesType()) + "」，请在对应 PI 流程办理单据，不生成国内购销合同";
        }
        if (hasLinkedPi) {
            var scenarios = mapper.selectLinkedPiScenarios(order.getId());
            // 仅对明确的国内内销历史记录补开合同，不解除既有 PI 的收款和出库约束。
            // 同时检查全部关联版本，避免因缺失或矛盾的历史归属把外销、欧洲订单误当内销。
            if (scenarios.isEmpty() || scenarios.stream().anyMatch(scenario ->
                    !"DOMESTIC".equals(scenario.tradeType())
                    || !"true".equals(scenario.domesticShipment())
                    || scenario.salesChannel() != null && !"CHINA_SALES".equals(scenario.salesChannel()))) {
                return "本订单关联外销、欧洲或销售归属待核对的 PI，请在对应 PI 流程办理并核对归属";
            }
        }
        if ("CANCELLED".equals(order.getStatus()) || "CLOSED".equals(order.getStatus())) return "已作废或关闭的订单不能生成购销合同";
        if (order.getOrderQty() <= 0) return "订单数量无效，请先核对订单";
        return null;
    }
}
