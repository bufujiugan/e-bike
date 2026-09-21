package com.bike.server.aftersales;

import com.bike.server.auth.UserAuthRecord;
import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import java.util.Objects;
import org.springframework.security.core.context.SecurityContextHolder;

/** Every after-sales resource, including binary downloads, uses the same account boundary. */
final class AfterSalesAccess {
    private AfterSalesAccess() {}
    static boolean isAgent() { return userType("AGENT") || CurrentOperator.hasRole("WEB_AGENT"); }
    static boolean isPda() { return userType("PDA_OPERATOR") || CurrentOperator.hasRole("PDA_OPERATOR"); }
    static Long requiredAgent() {
        Long id = CurrentOperator.agentId();
        if (id == null) throw new BusinessException("AGENT_NOT_BOUND", "当前账号未绑定代理商");
        return id;
    }
    static void requireWeb() {
        CurrentOperator.requireWebAccess();
        if (isPda()) throw new BusinessException("PERMISSION_DENIED", "PDA 账号不能访问售后管理");
    }
    static void requireRead() { requireWeb(); CurrentOperator.requirePermission("AFTER_SALES_READ"); }
    static void requireWrite() { requireWeb(); CurrentOperator.requirePermission("AFTER_SALES_WRITE"); }
    static void requireCommunicationWrite() {
        requireWeb();
        if (isAgent()) CurrentOperator.requirePermission("AFTER_SALES_WRITE");
        else CurrentOperator.requireAnyPermission("AFTER_SALES_WRITE", "AFTER_SALES_REVIEW", "AFTER_SALES_TECH", "AFTER_SALES_LOGISTICS");
    }
    static void requireInternal(String... permissions) {
        requireWeb();
        if (isAgent()) throw new BusinessException("FORBIDDEN", "代理商不能执行内部售后操作");
        CurrentOperator.requireAnyPermission(permissions);
    }
    static boolean canReadInternal() {
        return !isAgent() && !isPda() && (CurrentOperator.hasRole("SUPER_ADMIN")
            || CurrentOperator.hasPermission("AFTER_SALES_REVIEW") || CurrentOperator.hasPermission("AFTER_SALES_TECH"));
    }
    static boolean canReadAllCompanies() { return !isAgent() && !isPda() && (CurrentOperator.hasRole("SUPER_ADMIN") || CurrentOperator.hasPermission("AFTER_SALES_SCOPE_ALL")); }
    static Long salesScope() {
        if(isAgent() || canReadAllCompanies()) return null;
        if(CurrentOperator.id()==null) throw new BusinessException("PERMISSION_DENIED","当前账号缺少有效业务身份");
        return CurrentOperator.id();
    }
    static void requireAgentScope(AfterSalesMapper mapper, Long agentId) {
        if (isAgent()) {
            if (!Objects.equals(requiredAgent(), agentId)) throw new BusinessException("FORBIDDEN", "无权访问其他公司资料");
        } else if (!canReadAllCompanies() && (agentId == null || mapper.countSalesAgent(salesScope(), agentId) != 1))
            throw new BusinessException("FORBIDDEN", "当前公司不在负责范围内");
    }
    static void requireCaseScope(AfterSalesMapper mapper, AfterSalesCaseView c) {
        if (c == null) throw new BusinessException("AFTER_SALES_NOT_FOUND", "售后案例不存在");
        requireAgentScope(mapper, c.getAgentId());
    }
    private static boolean userType(String type) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getDetails() instanceof UserAuthRecord user && type.equals(user.userType());
    }
}
