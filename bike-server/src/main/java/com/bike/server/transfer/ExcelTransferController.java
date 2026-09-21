package com.bike.server.transfer;

import com.bike.server.common.ApiResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api")
public class ExcelTransferController {
    private final ExcelTransferService service;
    public ExcelTransferController(ExcelTransferService service) { this.service = service; }
    @GetMapping("/parts/import-template") public ResponseEntity<ByteArrayResource> partsTemplate() {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"parts-template.xlsx\"").body(new ByteArrayResource(service.partsTemplate()));
    }

    @PostMapping("/parts/import") public ApiResponse<ImportResult> partsImport(@RequestPart("file") MultipartFile file) { return ApiResponse.success("导入完成", service.importParts(file)); }
    @PostMapping("/master/{type}/import") public ApiResponse<ImportResult> masterImport(@PathVariable String type, @RequestPart("file") MultipartFile file) { return ApiResponse.success("导入完成", service.importMaster(type, file)); }
    @PostMapping("/orders/import") public ApiResponse<ImportResult> ordersImport(@RequestPart("file") MultipartFile file) { return ApiResponse.success("导入完成", service.importOrders(file)); }
    @GetMapping("/export/{module}") public ResponseEntity<ByteArrayResource> export(@PathVariable String module, @RequestParam(required=false) String keyword) { byte[] content = service.export(module, keyword); return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + module + ".xlsx\"").body(new ByteArrayResource(content)); }
    @GetMapping({"/parts/export","/vehicles/export","/inventory/export","/stock-in/export","/stock-out/export","/orders/export","/rework/export","/trace/export","/customs/export","/logs/export"})
    public ResponseEntity<ByteArrayResource> namedExport(HttpServletRequest request, @RequestParam(required=false) String keyword) {
        String path = request.getRequestURI();
        String module = path.substring("/api/".length(), path.length() - "/export".length());
        byte[] content = service.export(module, keyword);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + module + ".xlsx\"").body(new ByteArrayResource(content));
    }
}
