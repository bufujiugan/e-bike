package com.bike.server.part;

import com.bike.server.common.ApiResponse;
import com.bike.server.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/parts")
public class PartController {
    private final PartService partService;

    public PartController(PartService partService) { this.partService = partService; }

    @GetMapping
    public ApiResponse<PageResponse<PartView>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String partType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.success("查询成功", partService.list(keyword, partType, status, supplierId, page, pageSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<PartView> getById(@PathVariable long id) { return ApiResponse.success("查询成功", partService.getById(id)); }

    @GetMapping("/by-sn/{sn}")
    public ApiResponse<PartView> getBySn(@PathVariable String sn) { return ApiResponse.success("查询成功", partService.getBySn(sn)); }

    @PostMapping
    public ApiResponse<PartView> create(@Valid @RequestBody PartCreateRequest request) { return ApiResponse.success("创建成功", partService.create(request)); }

    @PutMapping("/{id}")
    public ApiResponse<PartView> update(@PathVariable long id, @Valid @RequestBody PartUpdateRequest request) { return ApiResponse.success("更新成功", partService.update(id, request)); }
}
