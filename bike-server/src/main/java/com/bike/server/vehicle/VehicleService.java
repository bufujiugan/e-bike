package com.bike.server.vehicle;

import com.bike.server.common.PageResponse;
import java.time.LocalDate;

public interface VehicleService {
    default PageResponse<VehicleView> list(String keyword, Integer status, Long modelId, long page, long pageSize) {
        return list(keyword, status, modelId, null, null, null, null, page, pageSize);
    }
    PageResponse<VehicleView> list(String keyword, Integer status, Long modelId, Long customerId, String orderNo,
                                   LocalDate fromDate, LocalDate toDate, long page, long pageSize);
    VehicleView getById(long id);
    VehicleView getByFrame(String frameSn);
    VehicleView getByQr(String qrCode);
    VehicleView create(VehicleCreateRequest request);
    VehicleView regenerateQr(long id);
}
