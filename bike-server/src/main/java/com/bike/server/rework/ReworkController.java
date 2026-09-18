package com.bike.server.rework;

import com.bike.server.common.ApiResponse;
import com.bike.server.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rework")
public class ReworkController { private final ReworkService service; public ReworkController(ReworkService service){this.service=service;} @GetMapping public ApiResponse<PageResponse<ReworkView>> list(@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long pageSize){return ApiResponse.success("查询成功",service.list(page,pageSize));} @GetMapping("/{id}") public ApiResponse<ReworkView> get(@PathVariable long id){return ApiResponse.success("查询成功",service.get(id));} @PostMapping public ApiResponse<ReworkView> create(@Valid @RequestBody ReworkRequest request){return ApiResponse.success("返工完成",service.create(request));} }
