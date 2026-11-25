package com.bike.server.order;

import com.bike.server.common.BusinessException;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record OrderTransportRequest(String transportArrangement,
                                    @Size(max = 500, message = "运输备注长度不能超过 500") String transportRemark) {
    private static final Set<String> ARRANGEMENTS = Set.of("SELLER_ARRANGED", "CUSTOMER_ARRANGED");

    public OrderTransportRequest {
        transportRemark = normalizeRemark(transportRemark);
    }

    static String normalizeRemark(String remark) {
        return remark == null || remark.isBlank() ? null : remark.strip();
    }

    public static String validateArrangement(String arrangement) {
        if (arrangement != null && !ARRANGEMENTS.contains(arrangement)) {
            throw new BusinessException("ORDER_TRANSPORT_INVALID", "请选择有效的运输安排");
        }
        return arrangement;
    }
}
