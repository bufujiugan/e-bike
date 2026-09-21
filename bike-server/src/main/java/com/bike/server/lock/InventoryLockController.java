package com.bike.server.lock;

import com.bike.server.common.ApiResponse;
import com.bike.server.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inventory-locks")
public class InventoryLockController {
    private final InventoryLockService service;
    public InventoryLockController(InventoryLockService service){this.service=service;}
    @GetMapping("/by-no/{lockNo}") public ApiResponse<InventoryLockView> getByNo(@PathVariable String lockNo){return ApiResponse.success("查询成功",service.getByNo(lockNo));}
    @GetMapping public ApiResponse<PageResponse<InventoryLockView>> list(@RequestParam(required=false) Long agentId,@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long pageSize){return ApiResponse.success("查询成功",service.list(agentId,page,pageSize));}
    @GetMapping("/legacy-conversions") public ApiResponse<PageResponse<LegacyConversionAuditView>> legacyConversions(@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long pageSize){return ApiResponse.success("查询成功",service.legacyConversions(page,pageSize));}
    @PostMapping public ApiResponse<InventoryLockView> create(@Valid @RequestBody InventoryLockCreateRequest request){return ApiResponse.success("库存锁定成功",service.create(request));}
    @PostMapping("/{id}/release") public ApiResponse<InventoryLockView> release(@PathVariable long id,@RequestParam(defaultValue="手工释放") String reason){return ApiResponse.success("库存锁定已释放",service.release(id,reason));}
    @PostMapping("/{id}/convert") public ApiResponse<InventoryLockView> convert(@PathVariable long id,@RequestParam int quantity){return ApiResponse.success("库存锁定已转订单",service.convert(id,quantity));}
}
