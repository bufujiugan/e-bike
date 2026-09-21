package com.bike.server.module;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface ModuleMapper {
    List<Map<String, Object>> selectRows(@Param("module") String module, @Param("agentId") Long agentId,
            @Param("financialAudit") boolean financialAudit);
    List<Map<String, Object>> selectRowsPage(@Param("module") String module, @Param("agentId") Long agentId,
            @Param("columns") List<String> columns, @Param("keyword") String keyword,
            @Param("sortColumn") String sortColumn, @Param("offset") long offset, @Param("limit") long limit,
            @Param("financialAudit") boolean financialAudit);
    long countRows(@Param("module") String module, @Param("agentId") Long agentId,
            @Param("columns") List<String> columns, @Param("keyword") String keyword,
            @Param("financialAudit") boolean financialAudit);
}
