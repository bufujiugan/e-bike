package com.bike.server.part;

import com.bike.server.common.ApiResponse;
import com.bike.server.common.PageResponse;
import com.bike.server.part.PartPricingService.Price;
import com.bike.server.part.PartPricingService.Update;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/part-pricing")
public class PartPricingController {
    private final PartPricingService service;

    public PartPricingController(PartPricingService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<Price>> list(@RequestParam(required = false) @Size(max = 128) String keyword,
                                                @RequestParam(defaultValue = "1") long page) {
        return ApiResponse.success(service.listPrices(keyword, page));
    }

    @PutMapping("/{id}")
    public ApiResponse<Price> update(@PathVariable @Positive long id, @Valid @RequestBody Update request) {
        return ApiResponse.success("零件价目已保存", service.updatePrice(id, request));
    }
}
