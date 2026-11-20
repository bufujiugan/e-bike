package com.bike.server.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 为待选类型订单确定销售类型。 */
public record SalesTypeRequest(@NotBlank(message = "请选择销售类型") @Pattern(regexp = "DOMESTIC|EXPORT|EUROPE", message = "销售类型无效") String salesType) {}
