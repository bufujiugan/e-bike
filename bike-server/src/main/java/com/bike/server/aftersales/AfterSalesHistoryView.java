package com.bike.server.aftersales;

import java.time.LocalDateTime;

/** Deliberately excludes audit snapshots, which may contain internal notes. */
public record AfterSalesHistoryView(Long id, String action, String operatorName, LocalDateTime operationTime) {}
