package com.bike.server.dashboard;

import java.util.List;

public record DashboardTrend(List<Point> points) {
    public record Point(String date, long assembled, long stockIn, long stockOut) { }
}
