package com.bike.server.aftersales;

import com.bike.server.common.PageResponse;

public interface AfterSalesService {
    java.util.Map<String, java.util.List<AfterSalesReferenceOption>> options();
    PageResponse<AfterSalesCaseView> list(String keyword, String status, long page, long pageSize);
    PageResponse<AfterSalesCaseView> list(AfterSalesCaseFilter filter, long page, long pageSize);
    AfterSalesDashboardView dashboard(AfterSalesCaseFilter filter);
    AfterSalesCaseView get(long id);
    AfterSalesCaseView create(AfterSalesCaseCreateRequest request);
    AfterSalesCaseView update(long id, AfterSalesCaseCreateRequest request);
    AfterSalesCaseView submit(long id);
    AfterSalesCaseView review(long id, AfterSalesReviewRequest request);
    AfterSalesCaseView saveSolution(long id, AfterSalesSolutionRequest request);
    AfterSalesCaseView startProcessing(long id);
    AfterSalesMessageView addMessage(long id, AfterSalesMessageRequest request);
    java.util.List<AfterSalesMessageView> messages(long id);
    AfterSalesShipmentView addShipment(long id, AfterSalesShipmentRequest request);
    java.util.List<AfterSalesShipmentView> shipments(long id);
    AfterSalesShipmentView dispatchShipment(long id, long shipmentId, AfterSalesDispatchRequest request);
    java.util.List<AfterSalesHistoryView> history(long id);
    AfterSalesCaseView resolve(long id);
    AfterSalesCaseView close(long id);
}
