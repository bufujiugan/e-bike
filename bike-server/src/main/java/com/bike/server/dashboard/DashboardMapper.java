package com.bike.server.dashboard;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

@Mapper
public interface DashboardMapper {
    Map<String, Object> selectSummaryCounts();
    Map<String,Object> selectInventorySummary();
    List<Map<String, Object>> selectVehicleStatusCounts();
    List<Map<String, Object>> selectRecentOperations(@org.apache.ibatis.annotations.Param("financialAudit") boolean financialAudit);
    List<Map<String, Object>> selectTrend(@org.apache.ibatis.annotations.Param("days") int days);
}
