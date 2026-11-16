package com.bike.server.europe;

import com.bike.server.common.PageResponse;
import java.util.List;

public interface EuropeSalesService {
    PageResponse<FactorySalesView> listFactoryOrders(String keyword, Long warehouseId, String businessType, long page, long pageSize);
    FactorySalesView getFactoryOrder(long id);
    FactorySalesView createFactoryOrder(FactorySalesRequest request);
    FactorySalesView updateFactoryOrder(long id, FactorySalesRequest request);
    FactorySalesView confirmFactoryStep(long id, String step, int revision, boolean completed, String note);
    List<SalesSettlementView> listSettlements(String type, long subjectId);
    List<SalesSettlementView> recordSettlement(String type, long subjectId, SalesSettlementRequest request);
    List<SalesSettlementView> supplementSettlement(String type, long subjectId, long entryId, SalesSettlementSupplement request);
    List<SalesSettlementView> reverseSettlement(String type, long subjectId, long entryId, String reason);
}
