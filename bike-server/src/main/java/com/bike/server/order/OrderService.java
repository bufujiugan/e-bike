package com.bike.server.order;

import com.bike.server.common.PageResponse;

public interface OrderService {
    PageResponse<SalesOrderView> list(String keyword, String status, long page, long pageSize);
    SalesOrderView get(long id);
    SalesOrderView getByNo(String orderNo);
    SalesOrderView create(SalesOrderCreateRequest request);
    SalesOrderView update(long id, SalesOrderCreateRequest request);
    SalesOrderView confirm(long id);
    default SalesOrderView cancel(long id) { return cancel(id, "业务作废"); }
    SalesOrderView cancel(long id, String reason);
}
