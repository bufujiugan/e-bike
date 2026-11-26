package com.bike.server.master;

import com.bike.server.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/freight-forwarders")
public class FreightForwarderController {
    private final FreightForwarderService service;

    public FreightForwarderController(FreightForwarderService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<FreightForwarder>> list() {
        return ApiResponse.success("查询成功", service.listForwarders());
    }

    @PostMapping
    public ApiResponse<FreightForwarder> create(@Valid @RequestBody FreightForwarderRequest request) {
        return ApiResponse.success("保存成功", service.createForwarder(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<FreightForwarder> update(@PathVariable long id, @Valid @RequestBody UpdateRequest request) {
        return ApiResponse.success("保存成功", service.updateForwarder(id, request.revision(), request.forwarder()));
    }

    public record UpdateRequest(@Positive int revision, @NotNull @Valid FreightForwarderRequest forwarder) {
    }
}
