package com.bike.server.dashboard;

public interface DashboardService {
    DashboardSummary getSummary();
    DashboardTrend getTrend(int days);
}
