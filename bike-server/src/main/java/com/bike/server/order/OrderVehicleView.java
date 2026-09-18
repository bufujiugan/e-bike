package com.bike.server.order;

import java.time.LocalDateTime;

/** 订单详情中的有效出库车辆。撤销出库的记录不返回。 */
public record OrderVehicleView(Long vehicleId, String frameSn, String stockOutNo, LocalDateTime stockOutTime) {
}
