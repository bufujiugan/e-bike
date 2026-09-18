package com.bike.server.dashboard;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

@Mapper
public interface DashboardMapper {
    Map<String, Object> selectSummaryCounts();
    List<Map<String, Object>> selectVehicleStatusCounts();
    List<Map<String, Object>> selectRecentOperations();
    List<Map<String, Object>> selectTrend(@org.apache.ibatis.annotations.Param("days") int days);
}
