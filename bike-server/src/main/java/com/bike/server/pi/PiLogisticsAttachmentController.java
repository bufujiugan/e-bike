package com.bike.server.pi;

import com.bike.server.common.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;

@RestController
@RequestMapping("/api/pis/{piId}/logistics/{logisticsId}/attachments")
public class PiLogisticsAttachmentController {
    private final PiLogisticsAttachmentService service;
    public PiLogisticsAttachmentController(PiLogisticsAttachmentService service){this.service=service;}
    @GetMapping public ApiResponse<List<PiLogisticsAttachment>> list(@PathVariable long piId,@PathVariable long logisticsId){return ApiResponse.success(service.list(piId,logisticsId));}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PiLogisticsAttachment> upload(@PathVariable long piId,@PathVariable long logisticsId,@RequestParam String requestId,@RequestParam String visibility,
            @RequestPart("file") MultipartFile file,MultipartHttpServletRequest request)throws IOException {
        if(request.getMultiFileMap().size()!=1||request.getFiles("file").size()!=1)throw new BusinessException("INVALID_PARAMETER","每次只能上传一个物流附件");
        return ApiResponse.success("物流附件已保存",service.upload(piId,logisticsId,requestId,visibility,file));
    }
    @GetMapping("/{id}/download") public ResponseEntity<byte[]> download(@PathVariable long piId,@PathVariable long logisticsId,@PathVariable String id) {
        var result=service.download(piId,logisticsId,id);var row=result.attachment();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
                .header("Content-Security-Policy","default-src 'none'; sandbox")
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(row.fileName(),StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(row.contentType())).contentLength(row.fileSize()).body(result.bytes());
    }
}
