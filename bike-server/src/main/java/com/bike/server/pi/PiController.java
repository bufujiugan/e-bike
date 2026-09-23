package com.bike.server.pi;

import com.bike.server.common.ApiResponse;
import com.bike.server.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pis")
public class PiController {
    private final PiService service;
    @org.springframework.beans.factory.annotation.Autowired private PiConversionService conversions;
    @org.springframework.beans.factory.annotation.Autowired private PiDispatchMapper dispatch;
    @org.springframework.beans.factory.annotation.Autowired private PiLogisticsService logisticsService;
    @org.springframework.beans.factory.annotation.Autowired private PiRevisionService revisions;
    @org.springframework.beans.factory.annotation.Autowired private PiExportArchive archives;
    @org.springframework.beans.factory.annotation.Autowired private PiImageService images;

    @GetMapping("/{id}/items/{itemId}/image") public ResponseEntity<byte[]> itemImage(@PathVariable long id,@PathVariable long itemId) {
        var item=service.get(id).getItems().stream().filter(i->i.getId()==itemId).findFirst()
                .orElseThrow(()->new com.bike.server.common.BusinessException("PI_IMAGE_NOT_FOUND","PI图片不存在"));
        byte[] bytes=images.bytes(item);
        if(bytes==null)throw new com.bike.server.common.BusinessException("PI_IMAGE_NOT_FOUND","PI图片不存在");
        return ResponseEntity.ok().header("X-Content-Type-Options","nosniff").header(HttpHeaders.CACHE_CONTROL,"no-store")
                .header(HttpHeaders.CONTENT_TYPE,bytes[0]==(byte)0x89?"image/png":"image/jpeg").body(bytes);
    }

    @GetMapping("/{id}/export-task") public ApiResponse<PiExportJob> exportTask(@PathVariable long id) {
        service.get(id);return ApiResponse.success("查询成功",archives.status(id));
    }
    @PostMapping("/{id}/export-task/retry") public ApiResponse<PiExportJob> retryExport(@PathVariable long id,@Valid @RequestBody PiExportArchive.Retry request) {
        service.get(id);return ApiResponse.success("导出已排队",archives.retry(id,request));
    }

    public PiController(PiService service) { this.service = service; }

    @PostMapping("/from-locks")
    public ApiResponse<PiView> convert(@Valid @RequestBody PiConversionRequest request) {
        return ApiResponse.success("锁定已原子生成履约订单和PI草稿", conversions.convert(request));
    }
    @GetMapping("/lock-options")
    public ApiResponse<PageResponse<java.util.Map<String,Object>>> lockOptions(@RequestParam long customerId,
            @RequestParam(required=false) Long agentId,@RequestParam(required=false) String keyword,
            @RequestParam(defaultValue="1") long page) {
        return ApiResponse.success("查询成功",conversions.options(customerId,agentId,keyword,page));
    }

    @GetMapping
    public ApiResponse<PageResponse<PiView>> list(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.success("查询成功", service.list(keyword, status, page, pageSize));
    }

    @GetMapping("/options")
    public ApiResponse<PiOptionsView> options(@RequestParam(required = false) String keyword) {
        return ApiResponse.success("查询成功", service.options(keyword));
    }

    @GetMapping("/{id}")
    public ApiResponse<PiView> get(@PathVariable long id) { return ApiResponse.success("查询成功", service.get(id)); }

    @PostMapping("/{id}/payments/{paymentId}/reverse")
    public ApiResponse<PiView> reversePayment(@PathVariable long id,@PathVariable long paymentId,@Valid @RequestBody PiPaymentReverseRequest request) {
        return ApiResponse.success("错误到账登记已撤回，请重新核对放行条件",service.reversePayment(id,paymentId,request));
    }

    @GetMapping("/{id}/dispatch-batches/{batchId}")
    public ApiResponse<java.util.Map<String,Object>> batch(@PathVariable long id,@PathVariable long batchId) {
        var pi=service.get(id);var batch=dispatch.get(batchId);
        if(batch==null||!java.util.Objects.equals(pi.getSeriesId(),batch.seriesId()))
            throw new com.bike.server.common.BusinessException("PI_BATCH_NOT_FOUND","该PI没有此出库批次");
        return ApiResponse.success("查询成功",java.util.Map.of("batch",batch,"vehicles",dispatch.vehicles(batchId)));
    }

    @PostMapping
    public ApiResponse<PiView> create(@Valid @RequestBody PiCreateRequest request) {
        return ApiResponse.success("PI 草稿已创建", service.create(request));
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<PiView> confirm(@PathVariable long id) { return ApiResponse.success("PI 已确认", service.confirm(id)); }

    @PostMapping("/{id}/revisions")
    public ApiResponse<PiView> revision(@PathVariable long id, @Valid @RequestBody PiRevisionRequest request) {
        return ApiResponse.success("PI修订草稿已创建", revisions.create(id, request));
    }

    @GetMapping("/{id}/revisions")
    public ApiResponse<java.util.List<PiView>> revisionHistory(@PathVariable long id) {
        return ApiResponse.success("查询成功", revisions.history(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<PiView> update(@PathVariable long id, @Valid @RequestBody PiCreateRequest request) {
        return ApiResponse.success("PI 草稿已更新", service.update(id, request));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<PiView> cancel(@PathVariable long id, @Valid @RequestBody PiCancelRequest request) {
        return ApiResponse.success("PI 已取消", service.cancel(id, request));
    }

    @PostMapping("/{id}/payments")
    public ApiResponse<PiView> payment(@PathVariable long id, @Valid @RequestBody PiPaymentRequest request) {
        return ApiResponse.success("收款已登记", service.recordPayment(id, request));
    }

    @PostMapping("/{id}/release")
    public ApiResponse<PiView> release(@PathVariable long id, @Valid @RequestBody PiReleaseRequest request) {
        return ApiResponse.success("PI 已放行发货", service.release(id, request));
    }

    @PostMapping("/{id}/logistics")
    public ApiResponse<PiView> logistics(@PathVariable long id, @Valid @RequestBody PiLogisticsRequest request) {
        return ApiResponse.success("物流已登记", service.addLogistics(id, request));
    }
    @PutMapping("/{id}/logistics/{logisticsId}")
    public ApiResponse<PiView> updateLogistics(@PathVariable long id,@PathVariable long logisticsId,@Valid @RequestBody PiLogisticsRequest request) {
        logisticsService.save(id,logisticsId,request);return ApiResponse.success("物流已更新",service.get(id));
    }
    @PostMapping("/{id}/logistics/{logisticsId}/withdraw")
    public ApiResponse<PiView> withdrawLogistics(@PathVariable long id,@PathVariable long logisticsId,@Valid @RequestBody PiLogisticsWithdraw request) {
        logisticsService.withdraw(id,logisticsId,request);return ApiResponse.success("物流已撤回，历史保留",service.get(id));
    }
    @GetMapping("/{id}/logistics/{logisticsId}/history")
    public ApiResponse<java.util.List<java.util.Map<String,Object>>> logisticsHistory(@PathVariable long id,@PathVariable long logisticsId) {
        return ApiResponse.success("查询成功",logisticsService.history(id,logisticsId));
    }

    @GetMapping(value = "/{id}/export.xlsx", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> excel(@PathVariable long id) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=pi-" + id + ".xlsx")
                .body(service.excel(id));
    }

    @GetMapping(value = "/{id}/export.pdf", produces = "application/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable long id) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=pi-" + id + ".pdf")
                .body(service.pdf(id));
    }
}
