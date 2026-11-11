package com.bike.server.pi;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PiPurchaseContractMapper {
    List<RevisionContract> selectRevisionContracts(@Param("piIds") List<Long> piIds);
    List<StockExecution> selectStockExecutions(@Param("stockNos") List<String> stockNos);
    record RevisionContract(long piId, long sourcePiId, int depth, String status, String detailJson) {}
    record StockExecution(String stockNo, Long executionPiId) {}
}
