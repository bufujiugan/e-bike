package com.bike.server.pi;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;

/** 单张PI只绑定一个发货仓库；历史单据缺失信息时由业务补录，不推断。 */
public record PiBusinessTerms(
        @NotNull @Positive Long warehouseId,
        @NotNull Boolean domesticShipment,
        @NotNull @Pattern(regexp = "GUANGTA|HONG_KONG") String collectionEntity) {}
