package com.bike.server.pi;

import com.bike.server.common.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pis")
public class PiReportController {
    private final PiReportService service;
    public PiReportController(PiReportService service){this.service=service;}
    @GetMapping("/dashboard") public ApiResponse<Map<String,Long>> summary(@RequestParam(required=false) String keyword){return ApiResponse.success(service.summary(keyword));}
    @GetMapping("/dashboard/{queue}") public ApiResponse<PageResponse<Map<String,Object>>> queue(@PathVariable String queue,
            @RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long pageSize){return ApiResponse.success(service.queue(queue,keyword,page,pageSize));}
    @GetMapping(value="/export.xlsx",produces="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> export(@RequestParam(required=false) String keyword,@RequestParam(required=false) String status) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=pi-list.xlsx")
                .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(service.export(keyword,status));
    }
}
