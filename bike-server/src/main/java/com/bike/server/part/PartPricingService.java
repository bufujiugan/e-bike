package com.bike.server.part;

import com.bike.server.common.PageResponse;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

public interface PartPricingService {
    PageResponse<Price> listPrices(String keyword, long page);
    Price updatePrice(long partId, Update request);

    record Price(long id, String partNo, String partName, boolean sellable,
                 @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unitPrice, String currency) {}
    record Update(@NotNull Boolean sellable,
                  @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal unitPrice,
                  @Pattern(regexp = "[A-Z]{3}", message = "币种须为三个大写字母") String currency) {}
}
