package com.bike.server.pi;

import java.time.LocalDateTime;
import java.util.List;

public record PiListCriteria(LocalDateTime createdFrom, LocalDateTime createdBefore,
        List<String> statuses, List<String> paymentStatuses, LocalDateTime dailyMonthStart,
        LocalDateTime dailyNextMonthStart, Boolean fulfillmentCompleted) {}
