package com.bike.server.agent;

import com.bike.server.common.ApiResponse;
import com.bike.server.common.CurrentOperator;
import com.bike.server.common.PageResponse;
import com.bike.server.lock.InventoryLockCreateRequest;
import com.bike.server.lock.InventoryLockView;
import com.bike.server.stock.InventoryRow;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final AgentInventoryService service;
    private final com.bike.server.lock.InventoryLockService lockService;

    public AgentController(AgentInventoryService service, com.bike.server.lock.InventoryLockService lockService) {
        this.service = service;
        this.lockService = lockService;
    }

    @GetMapping("/inventory") public ApiResponse<PageResponse<InventoryRow>> inventory(@RequestParam(defaultValue="1") long page, @RequestParam(defaultValue="20") long pageSize) { return ApiResponse.success("查询成功", service.inventory(page, pageSize)); }
    @GetMapping("/inventory-locks") public ApiResponse<PageResponse<InventoryLockView>> locks(@RequestParam(defaultValue="1") long page, @RequestParam(defaultValue="20") long pageSize) { return ApiResponse.success("查询成功", service.locks(page, pageSize)); }
    @PostMapping("/inventory-locks") public ApiResponse<InventoryLockView> create(@Valid @RequestBody InventoryLockCreateRequest request) { CurrentOperator.requireAnyRole("WEB_AGENT", "SUPER_ADMIN"); return ApiResponse.success("库存锁定成功", lockService.create(request)); }
    @PostMapping("/inventory-locks/{id}/release") public ApiResponse<InventoryLockView> release(@PathVariable long id, @RequestParam(defaultValue="代理商主动释放") String reason) { CurrentOperator.requireAnyRole("WEB_AGENT", "SUPER_ADMIN"); return ApiResponse.success("库存锁定已释放", lockService.release(id, reason)); }
    @PostMapping("/inventory-locks/{id}/convert") public ApiResponse<InventoryLockView> convert(@PathVariable long id, @RequestParam int quantity) { CurrentOperator.requireAnyRole("WEB_AGENT", "SUPER_ADMIN"); return ApiResponse.success("库存锁定已转订单", lockService.convert(id, quantity)); }
}
