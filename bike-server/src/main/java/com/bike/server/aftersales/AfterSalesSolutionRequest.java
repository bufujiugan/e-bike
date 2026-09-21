package com.bike.server.aftersales;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AfterSalesSolutionRequest(@Size(max=4000,message="技术判断长度不能超过4000") String technicalJudgment, @NotBlank(message="维修方案不能为空") @Size(max=4000,message="维修方案长度不能超过4000") String solution, @Size(max=4000,message="内部备注长度不能超过4000") String internalNote) {}
