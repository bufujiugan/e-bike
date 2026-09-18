package com.bike.server.dashboard;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.util.ArrayList;

@Service
public class DashboardServiceImpl implements DashboardService {
    private final DashboardMapper dashboardMapper;

    public DashboardServiceImpl(DashboardMapper dashboardMapper) {
        this.dashboardMapper = dashboardMapper;
    }

    @Override
    public DashboardSummary getSummary() {
        com.bike.server.common.CurrentOperator.requirePermission("DASHBOARD_READ");
        Map<String, Object> counts = dashboardMapper.selectSummaryCounts();
        List<DashboardSummary.Metric> metrics = List.of(
                new DashboardSummary.Metric("今日组装", value(counts, "todayAssembled"), "台", "+0%", "blue"),
                new DashboardSummary.Metric("当前物理库存", value(counts, "physicalInventory"), "台", "+0%", "green"),
                new DashboardSummary.Metric("今日入库", value(counts, "todayStockIn"), "台", "+0%", "orange"),
                new DashboardSummary.Metric("今日出库", value(counts, "todayStockOut"), "台", "+0%", "purple"));
        metrics = new java.util.ArrayList<>(metrics);
        metrics.add(new DashboardSummary.Metric("当前锁定库存", value(counts, "lockedInventory"), "台", "+0%", "orange"));
        metrics.add(new DashboardSummary.Metric("当前可用库存", Math.max(0, value(counts, "physicalInventory") - value(counts, "lockedInventory")), "台", "+0%", "green"));

        Map<String, DashboardSummary.StatusRow> statusByLabel = new java.util.LinkedHashMap<>();
        dashboardMapper.selectVehicleStatusCounts().forEach(row -> {
            String label = String.valueOf(row.get("label"));
            statusByLabel.put(label, new DashboardSummary.StatusRow(label, number(row.get("count")), String.valueOf(row.get("color"))));
        });
        List<DashboardSummary.StatusRow> statuses = new ArrayList<>();
        addStatus(statuses, statusByLabel, "待组装", "#94a3b8");
        addStatus(statuses, statusByLabel, "组装完成", "#1677c8");
        addStatus(statuses, statusByLabel, "已入库", "#16a36a");
        addStatus(statuses, statusByLabel, "已出库", "#8b5cf6");
        addStatus(statuses, statusByLabel, "返工中", "#f59e0b");
        addStatus(statuses, statusByLabel, "售后维修", "#ef4444");
        addStatus(statuses, statusByLabel, "报废", "#64748b");
        statusByLabel.forEach((label, status) -> {
            if (statuses.stream().noneMatch(item -> item.label().equals(label))) statuses.add(status);
        });
        List<DashboardSummary.RecentOperation> operations = dashboardMapper.selectRecentOperations().stream()
                .map(row -> new DashboardSummary.RecentOperation(
                        String.valueOf(row.get("time")), String.valueOf(row.get("action")),
                        String.valueOf(row.get("target")), String.valueOf(row.get("operator")),
                        String.valueOf(row.get("status"))))
                .toList();
        return new DashboardSummary(metrics, statuses, operations);
    }

    @Override
    public DashboardTrend getTrend(int days) {
        com.bike.server.common.CurrentOperator.requirePermission("DASHBOARD_READ");
        int range = Math.min(31, Math.max(1, days));
        Map<String, DashboardTrend.Point> grouped = new java.util.HashMap<>();
        for (Map<String, Object> row : dashboardMapper.selectTrend(range)) {
            String date = String.valueOf(row.get("date"));
            grouped.put(date, new DashboardTrend.Point(date, number(row.get("assembled")), number(row.get("stockIn")), number(row.get("stockOut"))));
        }
        List<DashboardTrend.Point> points = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = range - 1; i >= 0; i--) {
            String date = today.minusDays(i).toString();
            points.add(grouped.getOrDefault(date, new DashboardTrend.Point(date, 0, 0, 0)));
        }
        return new DashboardTrend(points);
    }

    private static long value(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static void addStatus(List<DashboardSummary.StatusRow> statuses,
                                  Map<String, DashboardSummary.StatusRow> statusByLabel,
                                  String label, String color) {
        statuses.add(statusByLabel.getOrDefault(label, new DashboardSummary.StatusRow(label, 0, color)));
    }
}
