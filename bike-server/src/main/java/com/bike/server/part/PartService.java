package com.bike.server.part;

import com.bike.server.common.PageResponse;
import org.springframework.core.io.Resource;

public interface PartService {
    default PageResponse<PartView> list(String keyword, String partType, String status, long page, long pageSize) {
        return list(keyword, partType, status, null, page, pageSize);
    }
    PageResponse<PartView> list(String keyword, String partType, String status, Long supplierId, long page, long pageSize);
    PartView getById(long id);
    PartView getBySn(String partSn);
    PartView create(PartCreateRequest request);
    PartView createWithPhoto(PartCreateRequest request, PartPhotoUpload photo);
    PhotoContent getPhoto(long id);
    PartView update(long id, PartUpdateRequest request);
    PartView updateWithPhoto(long id, PartUpdateRequest request, PartPhotoUpload photo, boolean removePhoto);

    record PhotoContent(Resource resource, String contentType, long fileSize) {}
}
