package com.bike.server.files;

import com.bike.server.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/file-reviews")
public class FileReviewController {
    private final FileReviewService service;
    public FileReviewController(FileReviewService service){this.service=service;}
    @GetMapping("/history") public ApiResponse<java.util.List<java.util.Map<String,Object>>> history(@RequestParam String objectKey){return ApiResponse.success(service.history(objectKey));}
    @GetMapping public ApiResponse<FileReviewService.Page> list(@RequestParam(required=false) String state,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize){return ApiResponse.success(service.list(state,page,pageSize));}
    @PostMapping("/reconcile") public ApiResponse<FileUploadRecord> reconcile(@RequestBody FileReviewService.Request request){return ApiResponse.success(service.reconcile(request));}
    @PostMapping("/cleanup") public ApiResponse<FileUploadRecord> cleanup(@RequestBody FileReviewService.Request request){return ApiResponse.success(service.cleanup(request));}
}
