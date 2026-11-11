package com.bike.server.customer;

import com.bike.server.auth.UserAuthRecord;
import com.bike.server.common.*;
import com.bike.server.order.*;
import com.bike.server.system.UserMapper;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import java.util.*;

@Service
public class CustomerPortalServiceImpl implements CustomerPortalService {
    private final CustomerPortalMapper mapper;
    private final UserMapper users;
    private final OrderMapper orders;
    private final CustomerScopeAccess scope;
    private final PasswordEncoder encoder;
    public CustomerPortalServiceImpl(CustomerPortalMapper mapper, UserMapper users, OrderMapper orders,
                                     CustomerScopeAccess scope, PasswordEncoder encoder) {
        this.mapper = mapper; this.users = users; this.orders = orders; this.scope = scope; this.encoder = encoder;
    }
    private long customerUserId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getDetails() instanceof UserAuthRecord user)
                || !"CUSTOMER".equals(user.userType()) || !user.enabled())
            throw new BusinessException("PERMISSION_DENIED", "仅客户账号可以访问客户工作台");
        return user.id();
    }
    @Override public Company getCompany() {
        var companies = mapper.selectCompanies(customerUserId());
        if (companies.size() != 1) throw new BusinessException("CUSTOMER_BINDING_INVALID", "客户公司未绑定或已停用，请联系管理员");
        return companies.getFirst();
    }
    @Override public List<Model> listModels() { getCompany(); return mapper.selectModels(); }
    @Override public PageResponse<Order> listOrders(long page) {
        long customerId = getCompany().id(), current = Math.min(100000, Math.max(1, page));
        return new PageResponse<>(mapper.selectOrders(customerId, (current - 1) * 20), current, 20, mapper.countOrders(customerId));
    }
    @Override @Transactional
    public Order submitOrder(OrderRequest request) {
        long userId = customerUserId();
        // 同账号请求串行化，事务内检查提交记录；响应丢失后的重试不会再生成订单。
        if (users.lockUser(userId) == null) throw new BusinessException("UNAUTHORIZED", "账号已失效");
        long customerId = getCompany().id();
        String remark = request.remark() == null || request.remark().isBlank() ? null : request.remark().trim();
        Submission previous = mapper.selectSubmission(userId, request.requestId());
        if (previous != null) {
            if (previous.customerId() != customerId || previous.modelId() != request.modelId()
                    || previous.quantity() != request.quantity() || !Objects.equals(previous.plannedShipDate(), request.plannedShipDate())
                    || !Objects.equals(previous.remark(), remark))
                throw new BusinessException("CUSTOMER_ORDER_CONFLICT", "同一提交标识不能用于不同订单内容");
            return mapper.selectOrder(previous.orderId());
        }
        if (orders.countEnabledModel(request.modelId()) != 1)
            throw new BusinessException("MODEL_NOT_FOUND", "车型不存在或已停用");
        var input = new SalesOrderCreateRequest(customerId, null, request.modelId(), request.quantity(), request.plannedShipDate(), remark);
        String orderNo = "SO" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        if (orders.insertOrder(orderNo, input, userId) != 1 || orders.insertItem(orderNo, input) != 1) throw failed();
        var created = orders.selectByNo(orderNo);
        if (created == null || orders.updateStatus(created.getId(), "PENDING") != 1
                || mapper.insertSubmission(created.getId(), userId, customerId, request, remark) != 1
                || orders.insertOperationLog("ORDER_CREATE", orderNo, "客户提交订单", userId, CurrentOperator.username(),
                    null, "PENDING", CurrentOperator.clientIp(), remark) != 1) throw failed();
        return mapper.selectOrder(created.getId());
    }
    @Override @Transactional
    public Account createAccount(AccountRequest request) {
        CurrentOperator.requireInternalWebAccess();
        CurrentOperator.requireRole("SUPER_ADMIN");
        if (users.selectByUsername(request.username()) != null) throw new BusinessException("USER_EXISTS", "用户名已存在");
        if (request.customerId() != null && request.companyName() != null && !request.companyName().isBlank())
            throw new BusinessException("INVALID_PARAMETER", "选择已有客户时不能同时新建公司");
        try {
            Long customerId = request.customerId();
            if (customerId == null) {
                if (request.companyName() == null || request.companyName().isBlank())
                    throw new BusinessException("INVALID_PARAMETER", "请输入客户公司名称");
                String code = "C" + UUID.randomUUID().toString().replace("-", "");
                if (mapper.insertCompany(code, request.companyName().trim()) != 1) throw failed();
                customerId = mapper.selectCompanyId(code);
            }
            var company = customerId == null ? null : mapper.selectCompany(customerId);
            if (company == null) throw new BusinessException("CUSTOMER_NOT_FOUND", "客户不存在或已停用");
            mapper.insertCustomerRole();
            if (mapper.insertUser(request, encoder.encode(request.password())) != 1) throw failed();
            var user = users.selectByUsername(request.username());
            if (user == null || mapper.insertUserRole(user.getId()) != 1
                    || mapper.insertCustomerBinding(user.getId(), customerId, CurrentOperator.id()) != 1
                    || users.insertOperationLog(String.valueOf(user.getId()), "创建客户登录账号", CurrentOperator.id(), CurrentOperator.username(),
                         null, "customerId=" + customerId, CurrentOperator.clientIp()) != 1) throw failed();
            return new Account(user.getId(), request.username(), customerId, company.name());
        } catch (DuplicateKeyException error) {
            // 唯一约束竞争转换为明确的业务冲突，整个开户事务回滚。
            throw new BusinessException("CUSTOMER_ACCOUNT_CONFLICT", "账号已存在，请刷新后重试");
        }
    }
    private void requireStaff() {
        CurrentOperator.requireInternalWebAccess();
        CurrentOperator.requirePermission("ORDER_READ");
        CurrentOperator.requirePermission("ORDER_WRITE");
    }
    @Override public List<Order> listUnreadOrders() {
        requireStaff();
        return mapper.selectUnreadOrders(CurrentOperator.id(), CurrentOperator.customerScopeUserId());
    }
    @Override @Transactional public void acknowledgeOrder(long orderId) {
        requireStaff();
        // 已读也按账号串行，防止多标签页重复写入审计。
        users.lockUser(CurrentOperator.id());
        var order = mapper.selectOrder(orderId);
        if (order == null || mapper.countSubmission(orderId) == 0) throw new BusinessException("ORDER_NOT_FOUND", "客户订单不存在");
        scope.requireCustomer(order.customerId());
        mapper.insertAcknowledgement(orderId, CurrentOperator.id());
    }
    private static BusinessException failed() { return new BusinessException("CUSTOMER_OPERATION_FAILED", "客户业务保存失败，请重试"); }
}

