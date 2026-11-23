package com.bike.server.order;

import java.time.LocalDateTime;

/** 订单操作记录，出库操作带对应的出库单号。 */
public record OrderOperationView(String businessType, String action, String operatorName,
                                 LocalDateTime operationTime, String stockOutNo) {
}
