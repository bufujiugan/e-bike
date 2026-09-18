package com.bike.server.dashboard;

import java.util.List;

public record DashboardSummary(
        List<Metric> metrics,
        List<StatusRow> statusRows,
        List<RecentOperation> recentOperations) {
    public record Metric(String label, long value, String unit, String trend, String tone) { }
    public record StatusRow(String label, long count, String color) { }
    public record RecentOperation(String time, String action, String target, String operator, String status) { }
}
