package com.bike.server.pi;

import com.bike.server.common.ApiResponse;
import com.bike.server.common.BusinessException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequestMapping("/api/pis/{piId}/payments/{paymentId}/attachments")
public class PiReceiptAttachmentController {
    private final PiReceiptAttachmentService service;
    public PiReceiptAttachmentController(PiReceiptAttachmentService service) { this.service = service; }
    @GetMapping public ApiResponse<List<PiReceiptAttachmentView>> list(@PathVariable long piId, @PathVariable long paymentId) {
        return ApiResponse.success(service.list(piId, paymentId));
    }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PiReceiptAttachmentView> upload(@PathVariable long piId, @PathVariable long paymentId,
            @RequestParam String requestId, @RequestPart("file") MultipartFile file, MultipartHttpServletRequest request) throws IOException {
        if (request.getMultiFileMap().size() != 1 || request.getFiles("file").size() != 1)
            throw new BusinessException("INVALID_PARAMETER", "每次只能上传一个收款凭证文件");
        return ApiResponse.success("收款凭证已上传", service.upload(piId, paymentId, requestId, file));
    }
    @GetMapping("/{attachmentId}/download")
    public ResponseEntity<Resource> download(@PathVariable long piId, @PathVariable long paymentId, @PathVariable String attachmentId) {
        var file = service.download(piId, paymentId, attachmentId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType())).contentLength(file.size()).body(file.resource());
    }
    @PostMapping(value="/batch",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<?> batch(@PathVariable long piId,@PathVariable long paymentId,@RequestParam List<String> requestIds,
            @RequestPart("files") List<MultipartFile> files,MultipartHttpServletRequest request) {
        return ApiResponse.success(com.bike.server.common.AttachmentBatch.upload(requestIds,files,request,(key,file)->service.upload(piId,paymentId,key,file)));
    }
}
