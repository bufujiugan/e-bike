package com.bike.server.aftersales;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AfterSalesReviewRequest(@NotBlank(message="审核动作不能为空") String action, @Size(max=1000,message="审核说明长度不能超过1000") String reason) {}
