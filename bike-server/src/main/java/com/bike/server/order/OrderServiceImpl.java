package com.bike.server.order;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.common.PageResponse;
import com.bike.server.production.ProductionGuard;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderServiceImpl implements OrderService {
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.common.CustomerScopeAccess customerScope;
    private final OrderMapper orderMapper;
    @Autowired private ProductionGuard guard;
    @Autowired private OrderInventoryGuard inventoryGuard;
    @Autowired private OrderReservationCoordinator reservations;

    public OrderServiceImpl(OrderMapper orderMapper) { this.orderMapper = orderMapper; }

    @Override
    public PageResponse<SalesOrderView> list(String keyword, String status, long page, long pageSize) {
        requireOrderRead();
        customerScope.lockForBusiness();
        long p = Math.max(1, page), size = Math.min(100, Math.max(1, pageSize));
        String normalized = keyword == null || keyword.isBlank() ? null : keyword.trim();
        Long agentId = CurrentOperator.hasRole("WEB_AGENT") ? CurrentOperator.agentId() : null;
        if (CurrentOperator.hasRole("WEB_AGENT") && agentId == null) {
            throw new BusinessException("AGENT_NOT_BOUND", "当前账号未绑定代理商");
        }
        var records = orderMapper.selectPage(normalized, status, agentId, (p - 1) * size, size);
        records.forEach(OrderServiceImpl::redactReservationForAgent);
        return new PageResponse<>(records, p, size, orderMapper.count(normalized, status, agentId));
    }

    @Override
    public SalesOrderView get(long id) {
        requireOrderRead();
        SalesOrderView order = orderMapper.selectById(id);
        if (order == null) throw new BusinessException("ORDER_NOT_FOUND", "订单不存在");
        checkAgentScope(order);
        return enrichDetails(order);
    }

    @Override
    public SalesOrderView getByNo(String orderNo) {
        requireOrderRead();
        if (orderNo == null || orderNo.isBlank()) throw new BusinessException("ORDER_INVALID", "订单号不能为空");
        SalesOrderView order = orderMapper.selectByNo(orderNo.trim());
        if (order == null) throw new BusinessException("ORDER_NOT_FOUND", "订单不存在");
        checkAgentScope(order);
        return enrichDetails(order);
    }

    @Override
    @Transactional
    public SalesOrderView create(SalesOrderCreateRequest request) {
        requireWrite();
        validateReferences(request);
        String no = "SO" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
        Long operatorId = CurrentOperator.id();
        if (orderMapper.insertOrder(no, request, operatorId) != 1 || orderMapper.insertItem(no, request) != 1) {
            throw new BusinessException("ORDER_CREATE_FAILED", "订单创建失败");
        }
        orderMapper.insertOperationLog("ORDER_CREATE", no, "创建订单", operatorId, CurrentOperator.username(),
                null, "DRAFT", CurrentOperator.clientIp(), request.remark());
        return orderMapper.selectByNo(no);
    }

    @Override
    @Transactional
    public SalesOrderView update(long id, SalesOrderCreateRequest request) {
        requireWrite();
        if (guard != null) guard.lock();
        orderMapper.selectForUpdate(id);
        if (inventoryGuard != null) inventoryGuard.requireEditable(id);
        SalesOrderView current = get(id);
        if (!Set.of("DRAFT", "PENDING").contains(current.getStatus())) {
            throw new BusinessException("INVALID_STATUS_TRANSITION", "只有草稿或待确认订单可编辑");
        }
        if (current.getShippedQty() > request.orderQty()) {
            throw new BusinessException("ORDER_QTY_INVALID", "订单数量不能小于已出库数量");
        }
        validateReferences(request);
        if (orderMapper.updateOrder(id, request) != 1 || orderMapper.updateItem(id, request) != 1) {
            throw new BusinessException("ORDER_UPDATE_FAILED", "订单更新失败");
        }
        orderMapper.insertOperationLog("ORDER_UPDATE", current.getOrderNo(), "更新订单", CurrentOperator.id(),
                CurrentOperator.username(), current.getStatus(), current.getStatus(), CurrentOperator.clientIp(), request.remark());
        return get(id);
    }

    @Override
    @Transactional
    public SalesOrderView confirm(long id) {
        requireWrite();
        if (guard != null) guard.lock();
        orderMapper.selectForUpdate(id);
        if (inventoryGuard != null) inventoryGuard.requireConfirmable(id);
        SalesOrderView order = get(id);
        if (!Set.of("DRAFT", "PENDING").contains(order.getStatus())) {
            throw new BusinessException("INVALID_STATUS_TRANSITION", "订单当前不可确认");
        }
        if (orderMapper.updateStatus(id, "CONFIRMED") != 1) throw new BusinessException("ORDER_UPDATE_FAILED", "订单确认失败");
        orderMapper.insertOperationLog("ORDER_UPDATE", order.getOrderNo(), "确认订单", CurrentOperator.id(),
                CurrentOperator.username(), order.getStatus(), "CONFIRMED", CurrentOperator.clientIp(), null);
        return get(id);
    }

    @Override
    @Transactional
    public SalesOrderView cancel(long id, String reason) {
        requireWrite();
        String normalizedReason = reason == null || reason.isBlank() ? "业务作废" : reason.trim();
        if (normalizedReason.length() > 500) throw new BusinessException("INVALID_PARAMETER", "作废原因长度不能超过 500");
        if (guard != null) guard.lock();
        orderMapper.selectForUpdate(id);
        SalesOrderView order = get(id);
        if (Set.of("SHIPPED", "PARTIAL_SHIPPED", "CLOSED").contains(order.getStatus())) {
            throw new BusinessException("INVALID_STATUS_TRANSITION", "已有出库记录的订单不可直接作废");
        }
        if (reservations != null) reservations.release(id, "取消订单：" + normalizedReason);
        if (orderMapper.updateStatus(id, "CANCELLED") != 1) throw new BusinessException("ORDER_UPDATE_FAILED", "订单作废失败");
        orderMapper.insertOperationLog("ORDER_UPDATE", order.getOrderNo(), "作废订单", CurrentOperator.id(),
                CurrentOperator.username(), order.getStatus(), "CANCELLED", CurrentOperator.clientIp(), normalizedReason);
        return get(id);
    }

    private static void requireWrite() {
        CurrentOperator.requireInternalWebAccess();
        CurrentOperator.requirePermission("ORDER_WRITE");
    }

    private static void requireOrderRead() {
        CurrentOperator.requireAnyPermission("ORDER_READ", "STOCK_OUT_OPERATE");
        if (CurrentOperator.hasRole("PDA_OPERATOR")) CurrentOperator.requireWorkstation("STOCK_OUT");
    }

    private void checkAgentScope(SalesOrderView order) {
        customerScope.requireCustomer(order.getCustomerId());
        if (CurrentOperator.hasRole("WEB_AGENT") && !Objects.equals(CurrentOperator.agentId(), order.getAgentId())) {
            throw new BusinessException("FORBIDDEN", "无权访问其他代理商订单");
        }
    }

    public static void redactReservationForAgent(SalesOrderView order) {
        if (!CurrentOperator.hasRole("WEB_AGENT")) return;
        order.setReservationHoldMode(null);
        order.setReservationHeldAt(null);
        order.setReservationExpiresAt(null);
        if (Set.of("PAID_HELD", "TIMED_ACTIVE").contains(Objects.toString(order.getReservationStatus(), ""))) {
            order.setReservationStatus("ACTIVE");
        }
    }

    private void validateReferences(SalesOrderCreateRequest request) {
        customerScope.requireCustomer(request.customerId());
        if (orderMapper.countEnabledCustomer(request.customerId()) != 1) throw new BusinessException("CUSTOMER_NOT_FOUND", "客户不存在或已停用");
        if (orderMapper.countEnabledModel(request.modelId()) != 1) throw new BusinessException("MODEL_NOT_FOUND", "车型不存在或已停用");
        if (request.agentId() != null && orderMapper.countEnabledAgent(request.agentId()) != 1) {
            throw new BusinessException("AGENT_NOT_FOUND", "代理商不存在或已停用");
        }
    }

    private SalesOrderView enrichDetails(SalesOrderView order) {
        order.setShippedVehicles(orderMapper.selectShippedVehicles(order.getId()));
        redactReservationForAgent(order);
        return order;
    }
}
