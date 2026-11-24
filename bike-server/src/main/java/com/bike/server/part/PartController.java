package com.bike.server.part;

import com.bike.server.common.ApiResponse;
import com.bike.server.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/parts")
public class PartController {
    private final PartService partService;

    public PartController(PartService partService) { this.partService = partService; }

    @GetMapping
    public ApiResponse<PageResponse<PartView>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String partType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.success("查询成功", partService.list(keyword, partType, status, supplierId, page, pageSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<PartView> getById(@PathVariable long id) { return ApiResponse.success("查询成功", partService.getById(id)); }

    @GetMapping("/by-sn/{sn}")
    public ApiResponse<PartView> getBySn(@PathVariable String sn) { return ApiResponse.success("查询成功", partService.getBySn(sn)); }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<PartView> create(@Valid @RequestBody PartCreateRequest request) { return ApiResponse.success("创建成功", partService.create(request)); }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PartView> createWithPhoto(@Valid @RequestPart("part") PartCreateRequest request,
                                                @RequestPart(value = "photo", required = false) MultipartFile photo) {
        return ApiResponse.success("创建成功", partService.createWithPhoto(request, PartPhotoUpload.from(photo)));
    }

    @GetMapping("/{id}/photo")
    public ResponseEntity<Resource> photo(@PathVariable long id) {
        var photo = partService.getPhoto(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(photo.contentType()))
                .contentLength(photo.fileSize()).body(photo.resource());
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<PartView> update(@PathVariable long id, @Valid @RequestBody PartUpdateRequest request) { return ApiResponse.success("更新成功", partService.update(id, request)); }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PartView> updateWithPhoto(@PathVariable long id,
                                                @Valid @RequestPart("part") PartUpdateRequest request,
                                                @RequestPart(value = "photo", required = false) MultipartFile photo,
                                                @RequestParam(defaultValue = "false") boolean removePhoto) {
        return ApiResponse.success("更新成功", partService.updateWithPhoto(id, request, PartPhotoUpload.from(photo), removePhoto));
    }
}
