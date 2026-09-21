package com.bike.server.aftersales;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AfterSalesMessageRequest(@NotBlank(message="留言不能为空") @Size(max=4000,message="留言长度不能超过4000") String body, @Size(max=32,message="可见范围长度不能超过32") String visibility) {}
