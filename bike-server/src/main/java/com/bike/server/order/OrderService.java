package com.bike.server.order;

import com.bike.server.common.PageResponse;

public interface OrderService {
    default PageResponse<SalesOrderView> list(String keyword, String status, long page, long pageSize) { return list(keyword, status, null, page, pageSize); }
    /** salesTypes 为逗号分隔的 DOMESTIC/EXPORT/EUROPE/PENDING；为空不按类型筛选。 */
    PageResponse<SalesOrderView> list(String keyword, String status, String salesTypes, long page, long pageSize);
    /** 为待选类型订单补选销售类型，选定后不可修改。 */
    SalesOrderView assignSalesType(long id, String salesType);
    SalesOrderView get(long id);
    SalesOrderView getByNo(String orderNo);
    SalesOrderView create(SalesOrderCreateRequest request);
    SalesOrderView update(long id, SalesOrderCreateRequest request);
    SalesOrderView updateTransport(long id, OrderTransportRequest request);
    SalesOrderView confirm(long id);
    default SalesOrderView cancel(long id) { return cancel(id, "业务作废"); }
    SalesOrderView cancel(long id, String reason);
}
