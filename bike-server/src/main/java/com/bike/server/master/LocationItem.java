package com.bike.server.master;

/** 仓库库位选择项，供入库工位和 Web 筛选使用。 */
public record LocationItem(Long id, Long warehouseId, String code, String name, String status) {
}
