package com.bike.server.pi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PiPurchaseContractServiceImpl implements PiPurchaseContractService {
    private final PiPurchaseContractMapper mapper;
    private final PiSeriesMapper series;
    private final ObjectMapper json;

    public PiPurchaseContractServiceImpl(PiPurchaseContractMapper mapper, PiSeriesMapper series, ObjectMapper json) {
        this.mapper = mapper;
        this.series = series;
        this.json = json;
    }

    @Override
    public PiPurchaseContract findForOrder(long orderId) {
        Long piId = series.currentPiForOrder(orderId);
        return piId == null ? null : findForPis(List.of(piId)).get(piId);
    }

    @Override
    public Map<Long, PiPurchaseContract> findForPis(Collection<Long> piIds) {
        var ids = piIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        var contracts = new LinkedHashMap<Long, PiPurchaseContract>();
        var resolved = new HashSet<Long>();
        // 与出库合同校验保持一致：只沿该版本的祖先链继承最近一次已确认记录。
        for (var revision : mapper.selectRevisionContracts(ids)) {
            if (!"COMPLETED".equals(revision.status()) || !resolved.add(revision.piId())) continue;
            if (revision.detailJson() == null) continue;
            try {
                var details = json.readValue(revision.detailJson(), PiWorkflowDetails.class);
                if (details != null) contracts.put(revision.piId(), new PiPurchaseContract(details.purchaseContractParty(), details.purchaseContractNo()));
            } catch (JsonProcessingException error) {
                // 保留原始异常栈并向上抛出，避免将损坏记录静默展示为未登记。
                throw new IllegalStateException("PI购销合同资料读取失败，piId=" + revision.sourcePiId(), error);
            }
        }
        return contracts;
    }

    @Override
    public Map<String, PiPurchaseContract> findForStockRecords(Collection<String> stockNos) {
        var numbers = stockNos.stream().filter(Objects::nonNull).distinct().toList();
        if (numbers.isEmpty()) return Map.of();
        var executions = mapper.selectStockExecutions(numbers);
        // 历史出库始终按执行版本读取，不能退回订单当前PI，否则修订后会串号。
        var contracts = findForPis(executions.stream().map(PiPurchaseContractMapper.StockExecution::executionPiId).toList());
        var result = new LinkedHashMap<String, PiPurchaseContract>();
        for (var execution : executions) {
            if (execution.executionPiId() == null) continue;
            var contract = contracts.get(execution.executionPiId());
            if (contract != null) result.put(execution.stockNo(), contract);
        }
        return result;
    }
}
