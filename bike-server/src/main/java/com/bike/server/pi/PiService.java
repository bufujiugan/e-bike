package com.bike.server.pi;

import com.bike.server.common.PageResponse;
import java.time.YearMonth;
import java.util.List;

public interface PiService {
    PiOptionsView options(String keyword);
    PageResponse<PiView> list(String keyword,String status,long page,long pageSize);
    PageResponse<PiView> listByWarehouse(String keyword, String status, Long warehouseId, long page, long pageSize);
    PageResponse<PiView> search(String keyword, String status, Long warehouseId, String tradeType, String salesChannel,
            PiListFilters filters, YearMonth referenceMonth, long page, long pageSize);
    PageResponse<PiView> listBySalesScenario(String keyword, String status, Long warehouseId,
            String tradeType, String salesChannel, long page, long pageSize);
    List<java.util.Map<String, Object>> listWarehouseOptions();
    PiView get(long id);
    java.util.Map<String, Object> getDispatchBatch(long piId, long batchId);
    PiView create(PiCreateRequest request);
    /** 页面新建：要求明确的销售入口（外销或欧洲），再按 create 保存草稿。 */
    default PiView createFromSalesEntry(PiCreateRequest request) { return create(request); }
    PiView update(long id, PiCreateRequest request);
    PiView cancel(long id, PiCancelRequest request);
    PiView confirm(long id);
    PiView recordPayment(long id, PiPaymentRequest request);
    PiView reversePayment(long id,long paymentId,PiPaymentReverseRequest request);
    PiView release(long id, PiReleaseRequest request);
    PiView addLogistics(long id, PiLogisticsRequest request);
    byte[] excel(long id);
    byte[] pdf(long id);
}
