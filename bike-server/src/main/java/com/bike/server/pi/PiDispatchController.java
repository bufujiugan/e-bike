package com.bike.server.pi;
import com.bike.server.common.*;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pi-dispatch")
public class PiDispatchController {
    private final PiDispatchService service;
    public PiDispatchController(PiDispatchService service){this.service=service;}
    @GetMapping("/options") public ApiResponse<PageResponse<Map<String,Object>>> options(@RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") long page){return ApiResponse.success("查询成功",service.options(keyword,page));}
    @GetMapping("/{piId}") public ApiResponse<Map<String,Object>> warehouse(@PathVariable long piId){return ApiResponse.success("查询成功",service.warehouse(piId));}
    @PostMapping("/{piId}") public ApiResponse<PiDispatchBatch> dispatch(@PathVariable long piId,@Valid @RequestBody PiDispatchRequest request){return ApiResponse.success("PI整批出库成功",service.dispatch(piId,request));}
    @GetMapping("/batches/{id}") public ApiResponse<Map<String,Object>> batch(@PathVariable long id){return ApiResponse.success("查询成功",service.warehouseBatch(id));}
}
