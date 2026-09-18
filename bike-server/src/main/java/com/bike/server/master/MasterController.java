package com.bike.server.master;

import com.bike.server.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class MasterController {
    private final MasterService masterService;
    public MasterController(MasterService masterService) { this.masterService = masterService; }
    @GetMapping("/api/master/{type}") public ApiResponse<List<MasterItem>> list(@PathVariable String type,@RequestParam(required=false) String keyword){return ApiResponse.success("查询成功",masterService.list(type,keyword));}
    @PostMapping("/api/master/{type}") public ApiResponse<MasterItem> create(@PathVariable String type,@Valid @RequestBody MasterCreateRequest request){return ApiResponse.success("创建成功",masterService.create(type,request));}
    @PutMapping("/api/master/{type}/{id}") public ApiResponse<MasterItem> update(@PathVariable String type,@PathVariable long id,@Valid @RequestBody MasterUpdateRequest request){return ApiResponse.success("更新成功",masterService.update(type,id,request));}
    @PutMapping("/api/master/{type}/{id}/status") public ApiResponse<MasterItem> updateStatus(@PathVariable String type,@PathVariable long id,@RequestParam boolean enabled){return ApiResponse.success("更新成功",masterService.updateStatus(type,id,enabled));}
    @GetMapping("/api/master/warehouses/{warehouseId}/locations") public ApiResponse<List<LocationItem>> locations(@PathVariable long warehouseId){return ApiResponse.success("查询成功",masterService.listLocations(warehouseId));}
    @PostMapping("/api/master/warehouses/{warehouseId}/locations") public ApiResponse<LocationItem> createLocation(@PathVariable long warehouseId,@Valid @RequestBody MasterCreateRequest request){return ApiResponse.success("库位创建成功",masterService.createLocation(warehouseId,request));}
    @PutMapping("/api/master/locations/{id}") public ApiResponse<LocationItem> updateLocation(@PathVariable long id,@Valid @RequestBody LocationUpdateRequest request){return ApiResponse.success("库位更新成功",masterService.updateLocation(id,request));}
    @PutMapping("/api/master/locations/{id}/status") public ApiResponse<LocationItem> updateLocationStatus(@PathVariable long id,@RequestParam boolean enabled){return ApiResponse.success("库位状态更新成功",masterService.updateLocationStatus(id,enabled));}
}
