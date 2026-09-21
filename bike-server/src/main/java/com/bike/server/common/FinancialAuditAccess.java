package com.bike.server.common;

/** Financial events require the same internal permissions as PI financial details. */
public final class FinancialAuditAccess {
    private FinancialAuditAccess() {}

    public static boolean allowed() {
        return !CurrentOperator.hasRole("WEB_AGENT") && !CurrentOperator.hasRole("PDA_OPERATOR")
                && (CurrentOperator.hasRole("SUPER_ADMIN")
                    || CurrentOperator.hasPermission("PI_PAYMENT_CONFIRM")
                    || CurrentOperator.hasPermission("PI_RELEASE"));
    }
}
