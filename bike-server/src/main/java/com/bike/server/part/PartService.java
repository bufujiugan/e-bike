package com.bike.server.part;

import com.bike.server.common.PageResponse;

public interface PartService {
    default PageResponse<PartView> list(String keyword, String partType, String status, long page, long pageSize) {
        return list(keyword, partType, status, null, page, pageSize);
    }
    PageResponse<PartView> list(String keyword, String partType, String status, Long supplierId, long page, long pageSize);
    PartView getById(long id);
    PartView getBySn(String partSn);
    PartView create(PartCreateRequest request);
    PartView update(long id, PartUpdateRequest request);
}
