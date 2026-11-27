package com.bike.server.customer;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import com.bike.server.common.PageResponse;
import com.bike.server.part.PartPricingMapper;
import com.bike.server.part.PartPricingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class CustomerStatusServiceImpl implements CustomerStatusService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CustomerStatusServiceImpl.class);
    private static final int PAGE_SIZE = 20;

    private final CustomerPortalService customerPortal;
    private final CustomerStatusMapper mapper;
    private final PartPricingMapper parts;

    public CustomerStatusServiceImpl(CustomerPortalService customerPortal, CustomerStatusMapper mapper,
                                     PartPricingMapper parts) {
        this.customerPortal = customerPortal;
        this.mapper = mapper;
        this.parts = parts;
    }

    @Override
    public PageResponse<OrderSummary> listOrders(long page) {
        var scope = requireScope("ORDER_READ");
        long current = pageNumber(page);
        Long userId = CurrentOperator.id();
        var orders = mapper.selectOrders(scope.customerId(), scope.agentId(), userId, null, null, offset(current));
        return new PageResponse<>(orders.stream().map(this::customerStatus).toList(), current, PAGE_SIZE,
                mapper.countOrders(scope.customerId(), scope.agentId(), userId));
    }

    @Override
    public OrderDetail getOrder(String kind, long id) {
        var scope = requireScope("ORDER_READ");
        if (!"ORDER".equals(kind) && !"PI".equals(kind)) throw orderNotFound();
        var matches = mapper.selectOrders(scope.customerId(), scope.agentId(), CurrentOperator.id(), kind, id, 0);
        if (matches.isEmpty()) throw orderNotFound();
        var logistics = "ORDER".equals(kind) ? mapper.selectOrderLogistics(id) : mapper.selectSeriesLogistics(id);
        return new OrderDetail(customerStatus(matches.getFirst()), logistics);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void removeCancelledOrder(String kind, long id) {
        var scope = requireScope("ORDER_READ");
        if (!"ORDER".equals(kind) && !"PI".equals(kind)) throw orderNotFound();
        Long userId = CurrentOperator.id();
        if (userId == null) throw new BusinessException("UNAUTHORIZED", "请先登录");
        int affected = mapper.insertCancelledOrderDismissal(scope.customerId(), scope.agentId(), userId, kind, id);
        // 重复请求可能返回零更新行，需要读取并发事务刚提交的删除记录。
        if (affected == 0 && !mapper.existsCancelledOrderDismissal(scope.customerId(), scope.agentId(), userId, kind, id)) {
            throw new BusinessException("ORDER_NOT_REMOVABLE", "只能删除当前账号可见的已取消订单");
        }
        LOGGER.info("客户工作台删除已取消订单，userId={}, orderKind={}, orderId={}", userId, kind, id);
    }

    @Override
    public PageResponse<Inventory> listInventory(long page) {
        var scope = requireScope("AGENT_INVENTORY_READ");
        long current = pageNumber(page);
        return new PageResponse<>(mapper.selectInventory(scope.agentId(), offset(current)), current, PAGE_SIZE,
                mapper.countInventory(scope.agentId()));
    }

    @Override
    public PageResponse<PartPricingService.Price> listParts(String keyword, long page) {
        requireScope(null);
        long current = pageNumber(page);
        String search = keyword == null ? null : keyword.strip();
        return new PageResponse<>(parts.selectPrices(search, true, offset(current)), current, PAGE_SIZE,
                parts.countPrices(search, true));
    }

    private Scope requireScope(String agentPermission) {
        CurrentOperator.requireAnyRole("WEB_CUSTOMER", "WEB_AGENT");
        if (CurrentOperator.hasRole("WEB_CUSTOMER")) return new Scope(customerPortal.getCompany().id(), null);
        if (agentPermission != null) CurrentOperator.requirePermission(agentPermission);
        Long agentId = CurrentOperator.agentId();
        if (agentId == null) throw new BusinessException("AGENT_NOT_BOUND", "当前账号未绑定所属客户");
        return new Scope(null, agentId);
    }

    private OrderSummary customerStatus(OrderSummary order) {
        String status;
        if ("CANCELLED".equals(order.status())) status = "CANCELLED";
        else if (order.orderQty() > 0 && order.shippedQty() >= order.orderQty()) status = "SHIPPED";
        else if ("CLOSED".equals(order.status())) status = "CLOSED";
        else if (order.shippedQty() > 0) status = "PARTIAL_SHIPPED";
        else if (List.of("DRAFT", "PENDING").contains(order.status())) status = "PENDING";
        else status = "PREPARING";
        return new OrderSummary(order.id(), order.kind(), order.orderNo(), order.modelName(),
                order.orderQty(), order.shippedQty(), status);
    }

    private static BusinessException orderNotFound() {
        return new BusinessException("ORDER_NOT_FOUND", "订单不存在");
    }

    private static long pageNumber(long page) { return Math.clamp(page, 1, 100_000); }
    private static long offset(long page) { return (page - 1) * PAGE_SIZE; }

    private record Scope(Long customerId, Long agentId) {}
}
