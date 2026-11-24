package com.bike.server.pi;

import java.time.YearMonth;
import java.time.ZoneId;

public record PiListFilters(String createdMonth, String statuses, String paymentStatuses,
        Boolean dailyFollowUp, Boolean fulfillmentCompleted) {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    public static final PiListFilters EMPTY = new PiListFilters(null, null, null, null, null);

    public boolean isActive() {
        return createdMonth != null || statuses != null || paymentStatuses != null
                || Boolean.TRUE.equals(dailyFollowUp) || fulfillmentCompleted != null;
    }

    static YearMonth currentMonth() {
        return YearMonth.now(BUSINESS_ZONE);
    }
}
