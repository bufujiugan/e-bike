package com.bike.server.pi;

import com.bike.server.common.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;

@RestController
@RequestMapping("/api/pis/{piId}/confirmation-attachments")
public class PiConfirmationController {
    private final PiConfirmationService service;
    public PiConfirmationController(PiConfirmationService service){this.service=service;}
    @GetMapping public ApiResponse<List<PiConfirmationAttachment>> list(@PathVariable long piId){return ApiResponse.success(service.list(piId));}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PiConfirmationAttachment> upload(@PathVariable long piId,@RequestParam String requestId,@RequestParam String visibility,
            @RequestPart("file") MultipartFile file,MultipartHttpServletRequest request)throws IOException {
        if(request.getMultiFileMap().size()!=1||request.getFiles("file").size()!=1)throw new BusinessException("INVALID_PARAMETER","每次只能上传一个确认附件");
        return ApiResponse.success("确认附件已保存",service.upload(piId,requestId,visibility,file));
    }
    @GetMapping("/{id}/download") public ResponseEntity<byte[]> download(@PathVariable long piId,@PathVariable String id) {
        var result=service.download(piId,id);var row=result.attachment();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
                .header("Content-Security-Policy","default-src 'none'; sandbox")
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(row.fileName(),StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(row.contentType())).contentLength(row.fileSize()).body(result.bytes());
    }
    @PostMapping(value="/batch",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<?> batch(@PathVariable long piId,@RequestParam List<String> requestIds,@RequestParam String visibility,
            @RequestPart("files") List<MultipartFile> files,MultipartHttpServletRequest request) {
        return ApiResponse.success(AttachmentBatch.upload(requestIds,files,request,(key,file)->service.upload(piId,key,visibility,file)));
    }
}
