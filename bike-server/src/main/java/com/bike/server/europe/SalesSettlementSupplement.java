package com.bike.server.europe;

import jakarta.validation.constraints.Size;

/** 补录参考信息不修改款项金额、币种及实际发生时间。 */
public record SalesSettlementSupplement(@Size(max=200) String counterparty,
        @Size(max=300) String referenceNo, @Size(max=2000) String remark) {}
