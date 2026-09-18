package com.bike.server.agent;

import com.bike.server.common.PageResponse;

public interface PurchaseIntentService {
    PageResponse<PurchaseIntentView> list(long page, long pageSize);
    PurchaseIntentView create(PurchaseIntentCreateRequest request);
}
