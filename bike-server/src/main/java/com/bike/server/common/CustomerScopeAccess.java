package com.bike.server.common;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Customer authorization supplements, and never grants, a functional permission. */
@Component
public class CustomerScopeAccess {
    private final CustomerScopeMapper mapper;
    public CustomerScopeAccess(CustomerScopeMapper mapper) { this.mapper = mapper; }

    public void requireCustomer(Long customerId) {
        // Agent services must still enforce their existing agent ownership rules.
        if (CurrentOperator.hasRole("WEB_AGENT")) return;
        Long user = CurrentOperator.customerScopeUserId();
        if (user == null) return;
        if (user <= 0 || customerId == null) throw denied();
        lockForBusiness();
        // A locking read also sees revocations under a caller's REPEATABLE_READ transaction.
        boolean writing=TransactionSynchronizationManager.isActualTransactionActive() && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        if ((writing ? mapper.allowed(user, customerId) : mapper.readable(user, customerId)) == null) throw denied();
    }

    public void lockForBusiness() {
        Long user = CurrentOperator.customerScopeUserId();
        if (user != null && user > 0 && TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                && mapper.lockReader(user) == null) throw denied();
    }

    public static void requireAll() {
        CurrentOperator.requireInternalWebAccess();
        if (CurrentOperator.customerScopeUserId() != null) throw denied();
    }

    private static BusinessException denied() {
        return new BusinessException("CUSTOMER_SCOPE_DENIED", "当前账号未获授权访问该客户资料");
    }
}
