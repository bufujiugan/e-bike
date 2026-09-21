package com.bike.server.aftersales;

import com.bike.server.common.ApiResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/after-sales/cases/{caseId}/attachments")
public class AfterSalesAttachmentController {
    private final AfterSalesAttachmentService service;
    public AfterSalesAttachmentController(AfterSalesAttachmentService service) { this.service = service; }
    @GetMapping public ApiResponse<List<AfterSalesAttachmentView>> list(@PathVariable long caseId) { return ApiResponse.success(service.list(caseId)); }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<AfterSalesAttachmentView>> upload(@PathVariable long caseId, @RequestPart("files") List<MultipartFile> files,
        @RequestParam(defaultValue = "CUSTOMER") String visibility, @RequestParam(defaultValue = "OTHER") String category,
        @RequestParam(required = false) Long messageId) throws IOException {
        return ApiResponse.success("附件已上传", service.upload(caseId, files, visibility, category, messageId));
    }
    @GetMapping("/{attachmentId}/download") public ResponseEntity<Resource> download(@PathVariable long caseId, @PathVariable long attachmentId) {
        var file = service.download(caseId, attachmentId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "default-src 'none'; sandbox")
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
            .contentType(MediaType.parseMediaType(file.contentType())).contentLength(file.size()).body(file.resource());
    }
}
