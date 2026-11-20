package com.bike.server.module;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface ModuleMapper {
    List<Map<String, Object>> selectRows(@Param("module") String module, @Param("agentId") Long agentId,
            @Param("financialAudit") boolean financialAudit, @Param("salesTypes") List<String> salesTypes);
    default List<Map<String, Object>> selectRows(String module, Long agentId, boolean financialAudit) {
        return selectRows(module, agentId, financialAudit, null);
    }
    /** salesTypes 仅用于订单模块，由 Service 固定校验。 */
    List<Map<String, Object>> selectRowsPage(@Param("module") String module, @Param("agentId") Long agentId,
            @Param("columns") List<String> columns, @Param("keyword") String keyword,
            @Param("sortColumn") String sortColumn, @Param("offset") long offset, @Param("limit") long limit,
            @Param("financialAudit") boolean financialAudit, @Param("salesTypes") List<String> salesTypes);
    default List<Map<String, Object>> selectRowsPage(String module, Long agentId, List<String> columns, String keyword,
            String sortColumn, long offset, long limit, boolean financialAudit) {
        return selectRowsPage(module, agentId, columns, keyword, sortColumn, offset, limit, financialAudit, null);
    }
    long countRows(@Param("module") String module, @Param("agentId") Long agentId,
            @Param("columns") List<String> columns, @Param("keyword") String keyword,
            @Param("financialAudit") boolean financialAudit, @Param("salesTypes") List<String> salesTypes);
    default long countRows(String module, Long agentId, List<String> columns, String keyword, boolean financialAudit) {
        return countRows(module, agentId, columns, keyword, financialAudit, null);
    }
}
