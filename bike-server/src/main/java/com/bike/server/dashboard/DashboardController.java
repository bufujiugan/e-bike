package com.bike.server.dashboard;

import com.bike.server.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    public ApiResponse<DashboardSummary> summary() {
        return ApiResponse.success("查询成功", dashboardService.getSummary());
    }

    @GetMapping("/trend")
    public ApiResponse<DashboardTrend> trend(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "7") int days) {
        return ApiResponse.success("查询成功", dashboardService.getTrend(days));
    }
}
