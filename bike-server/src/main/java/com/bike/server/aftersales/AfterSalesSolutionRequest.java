package com.bike.server.aftersales;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;

public record AfterSalesSolutionRequest(@Size(max=4000) String technicalJudgment,
    @NotBlank @Size(max=4000) String solution, @Size(max=4000) String internalNote,
    Long previousVersionId, @Size(max=2000) String faultCause, @Size(max=4000) String customerActions,
    @Size(max=2000) String repairNotes, @Size(max=32) String warrantyStatus,
    @Size(max=3) String currency, @Size(max=1000) String chargeReason,
    @Size(max=100) List<@Valid Part> parts) {
    public record Part(@NotBlank @Size(max=128) String partCode,@NotBlank @Size(max=255) String partName,
        int quantity, BigDecimal unitCharge, boolean returnRequired) {}
    public AfterSalesSolutionRequest(String judgment,String solution,String note) {
        this(judgment,solution,note,null,null,null,null,"PENDING","CNY",null,List.of());
    }
}
