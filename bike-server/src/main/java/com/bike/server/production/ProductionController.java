package com.bike.server.production;

import static com.bike.server.production.ProductionContracts.*;

import com.bike.server.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/production")
public class ProductionController {
  private final ProductionService service;
  private final ProductionLabelService labels;
  private final ProductionAttachmentService attachments;
  private final ProductionExcelService excel;
  private final ScanFailureService scanFailures;

  public ProductionController(
      ProductionService service,
      ProductionLabelService labels,
      ProductionAttachmentService attachments,
      ProductionExcelService excel, ScanFailureService scanFailures) {
    this.service = service;
    this.labels = labels;
    this.attachments = attachments;
    this.excel = excel;
    this.scanFailures = scanFailures;
  }

  @GetMapping("/scan-failures")
  public ApiResponse<?> scanFailures(@RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "1") long page) {
    return ApiResponse.success("查询成功", scanFailures.list(keyword, page));
  }

  @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<Void>> invalidRequest(
      org.springframework.web.bind.MethodArgumentNotValidException error,
      jakarta.servlet.http.HttpServletRequest httpRequest) {
    var fieldError = error.getBindingResult().getFieldError();
    String reason = fieldError == null ? "请求参数校验失败" : fieldError.getDefaultMessage();
    if (error.getBindingResult().getTarget() instanceof ScanRequest scan) {
      var match = java.util.regex.Pattern.compile("^/api/production/tasks/([^/]+)/scan$")
          .matcher(httpRequest.getRequestURI());
      if (match.matches()) scanFailures.record(match.group(1), scan,
          new com.bike.server.common.BusinessException("INVALID_PARAMETER", reason));
    } else if (error.getBindingResult().getTarget() instanceof WorkOrderScanRequest scan) {
      var match = java.util.regex.Pattern.compile("^/api/production/work-orders/([^/]+)/scan$")
          .matcher(httpRequest.getRequestURI());
      if (match.matches()) scanFailures.record(
          scan.taskId() == null ? "WORK-ORDER-" + match.group(1) : scan.taskId(),
          new ScanRequest(scan.code(), scan.expectedType()),
          new com.bike.server.common.BusinessException("INVALID_PARAMETER", reason));
    }
    return ResponseEntity.badRequest().body(ApiResponse.failure("INVALID_PARAMETER", reason));
  }

  @GetMapping("/skus/import-template")
  public ResponseEntity<byte[]> skuTemplate() {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=sku-template.xlsx")
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(excel.template());
  }

  @PostMapping("/skus/import")
  public ApiResponse<?> importSkus(@RequestPart MultipartFile file) {
    return ApiResponse.success("导入完成", excel.importSkus(file));
  }

  @GetMapping("/skus")
  public ApiResponse<?> skus() {
    return ApiResponse.success("查询成功", service.listSkus());
  }

  @GetMapping("/skus/{id}/names")
  public ApiResponse<?> skuNames(@PathVariable long id) {
    return ApiResponse.success("查询成功", service.listSkuNames(id));
  }

  @PutMapping("/skus/{id}/names")
  public ApiResponse<?> saveSkuNames(
      @PathVariable long id, @Valid @RequestBody SkuNamesRequest request) {
    service.saveSkuNames(id, request.names());
    return ApiResponse.success("保存成功", null);
  }

  @GetMapping("/barcodes/ean/{ean}")
  public ApiResponse<?> eanBarcode(@PathVariable String ean) {
    return ApiResponse.success("生成成功", service.eanBarcode(ean));
  }

  @GetMapping("/inventory")
  public ApiResponse<?> inventory(
      @RequestParam(required = false) Long skuId,
      @RequestParam(required = false) Long warehouseId, @RequestParam(required = false) Long locationId) {
    return ApiResponse.success("查询成功", service.inventory(skuId, warehouseId, locationId));
  }

  @PostMapping("/skus")
  public ApiResponse<?> createSku(@Valid @RequestBody SkuRequest r,
      @RequestParam(required = false) String imageDraftToken) {
    service.createSku(r, imageDraftToken);
    return ApiResponse.success("保存成功", null);
  }

  @PostMapping("/sku-image-drafts/{draftToken}/batch")
  public ApiResponse<?> uploadSkuDraftBatch(@PathVariable String draftToken,
      @RequestParam java.util.List<String> requestIds,
      @RequestPart("files") java.util.List<MultipartFile> files,
      org.springframework.web.multipart.MultipartHttpServletRequest request) {
    return ApiResponse.success(com.bike.server.common.AttachmentBatch.upload(
        requestIds, files, request, (key, file) -> attachments.uploadSkuDraft(draftToken, key, file)));
  }

  @DeleteMapping("/sku-image-drafts/{draftToken}")
  public ApiResponse<?> discardSkuDraft(@PathVariable String draftToken) {
    attachments.discardSkuDraft(draftToken);
    return ApiResponse.success("图片草稿已清理", null);
  }

  @GetMapping("/skus/{id}/images")
  public ApiResponse<?> skuImages(@PathVariable long id) {
    return ApiResponse.success("查询成功", attachments.skuImages(id));
  }

  @PostMapping("/skus/{id}/images")
  public ApiResponse<?> uploadSku(@PathVariable long id, @RequestParam(required=false) String requestId, @RequestPart MultipartFile file, org.springframework.web.multipart.MultipartHttpServletRequest request)
      throws java.io.IOException {
    com.bike.server.common.AttachmentBatch.requireSingle(request);
    return ApiResponse.success("上传成功", attachments.uploadSku(id, requestId == null ? java.util.UUID.randomUUID().toString() : requestId, file));
  }

  @PostMapping("/skus/{id}/images/batch")
  public ApiResponse<?> uploadSkuBatch(@PathVariable long id, @RequestParam java.util.List<String> requestIds,
      @RequestPart("files") java.util.List<MultipartFile> files, org.springframework.web.multipart.MultipartHttpServletRequest request) {
    return ApiResponse.success(com.bike.server.common.AttachmentBatch.upload(requestIds,files,request,(key,file)->attachments.uploadSku(id,key,file)));
  }

  @PutMapping("/skus/{id}")
  public ApiResponse<?> updateSku(@PathVariable long id, @Valid @RequestBody SkuRequest r) {
    service.saveSku(id, r);
    return ApiResponse.success("保存成功", null);
  }

  @GetMapping("/templates")
  public ApiResponse<?> templates() {
    return ApiResponse.success("查询成功", service.listTemplates());
  }

  @PutMapping("/templates/{id}")
  public ApiResponse<?> template(@PathVariable long id, @Valid @RequestBody TemplateRequest r) {
    service.updateTemplate(id, r);
    return ApiResponse.success("保存成功", null);
  }

  @GetMapping("/work-orders")
  public ApiResponse<?> workOrders(
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "1") long page,
      @RequestParam(required = false) Long skuId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) java.time.LocalDate fromDate,
      @RequestParam(required = false) java.time.LocalDate toDate) {
    return ApiResponse.success(
        "查询成功", service.listWorkOrders(keyword, page, skuId, status, fromDate, toDate));
  }

  @PostMapping("/work-orders")
  public ApiResponse<?> createWorkOrder(@Valid @RequestBody WorkOrderRequest request) {
    return ApiResponse.success("生产工单已建立", service.createWorkOrder(request));
  }

  @GetMapping("/work-orders/{id}")
  public ApiResponse<?> workOrder(
      @PathVariable long id, @RequestParam(defaultValue = "1") long taskPage) {
    return ApiResponse.success("查询成功", service.getWorkOrder(id, taskPage));
  }

  @PostMapping("/work-orders/{id}/scan")
  public ApiResponse<?> scanWorkOrder(
      @PathVariable long id, @Valid @RequestBody WorkOrderScanRequest request) {
    return ApiResponse.success("扫码完成", service.scanWorkOrder(id, request));
  }

  @PostMapping("/work-orders/{id}/pause")
  public ApiResponse<?> pauseWorkOrder(
      @PathVariable long id, @Valid @RequestBody WorkOrderStatusRequest request) {
    service.pauseWorkOrder(id, request.reason());
    return ApiResponse.success("生产工单已暂停", null);
  }

  @PostMapping("/work-orders/{id}/resume")
  public ApiResponse<?> resumeWorkOrder(
      @PathVariable long id, @Valid @RequestBody WorkOrderStatusRequest request) {
    service.resumeWorkOrder(id, request.reason());
    return ApiResponse.success("生产工单已恢复", null);
  }

  @GetMapping("/tasks")
  public ApiResponse<?> tasks(
      @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "1") long page,
      @RequestParam(required = false) Long skuId, @RequestParam(required = false) String status,
      @RequestParam(required = false) java.time.LocalDate fromDate, @RequestParam(required = false) java.time.LocalDate toDate) {
    return ApiResponse.success("查询成功", service.listTasks(keyword, page, skuId, status, fromDate, toDate));
  }

  @PostMapping("/tasks")
  public ApiResponse<?> createTask(@Valid @RequestBody TaskRequest r) {
    return ApiResponse.success("任务已建立", service.createTask(r));
  }

  @GetMapping("/tasks/{id}")
  public ApiResponse<?> task(@PathVariable String id) {
    return ApiResponse.success("查询成功", service.getTask(id));
  }

  @PostMapping("/tasks/{id}/scan")
  public ApiResponse<?> scan(@PathVariable String id, @Valid @RequestBody ScanRequest r) {
    return ApiResponse.success("扫码完成", service.scan(id, r));
  }

  @PostMapping("/tasks/{id}/complete")
  public ApiResponse<?> complete(@PathVariable String id) {
    return ApiResponse.success("组装完成", service.complete(id));
  }

  @PostMapping("/tasks/{id}/cancel")
  public ApiResponse<?> cancel(@PathVariable String id, @Valid @RequestBody ReasonRequest r) {
    service.cancel(id, r.reason());
    return ApiResponse.success("任务已取消", null);
  }

  @GetMapping("/issues")
  public ApiResponse<?> issues(
      @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "1") long page) {
    return ApiResponse.success("查询成功", service.listIssues(keyword, page));
  }

  @GetMapping("/quarantined-parts")
  public ApiResponse<?> quarantinedParts(@RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "1") long page) {
    return ApiResponse.success("查询成功", service.listQuarantinedParts(keyword, page));
  }

  @PostMapping("/quarantined-parts/{code}/release")
  public ApiResponse<?> releasePart(
      @PathVariable String code, @Valid @RequestBody ReasonRequest r) {
    service.releasePart(code, r.reason());
    return ApiResponse.success("复检放行成功", null);
  }

  @PostMapping("/issues")
  public ApiResponse<?> issue(@Valid @RequestBody IssueRequest r) {
    return ApiResponse.success("已登记并隔离", service.createIssue(r));
  }

  @PostMapping("/issues/{id}/resolve")
  public ApiResponse<?> resolve(@PathVariable String id, @Valid @RequestBody ReasonRequest r) {
    service.resolveIssue(id, r.reason());
    return ApiResponse.success("已解除隔离", null);
  }

  @PostMapping("/issues/{id}/images")
  public ApiResponse<?> upload(@PathVariable String id, @RequestParam(required=false) String requestId, @RequestPart MultipartFile file, org.springframework.web.multipart.MultipartHttpServletRequest request)
      throws java.io.IOException {
    com.bike.server.common.AttachmentBatch.requireSingle(request);
    return ApiResponse.success("上传成功", attachments.upload(id, requestId == null ? java.util.UUID.randomUUID().toString() : requestId, file));
  }

  @PostMapping("/issues/{id}/images/batch")
  public ApiResponse<?> uploadIssueBatch(@PathVariable String id, @RequestParam java.util.List<String> requestIds,
      @RequestPart("files") java.util.List<MultipartFile> files, org.springframework.web.multipart.MultipartHttpServletRequest request) {
    return ApiResponse.success(com.bike.server.common.AttachmentBatch.upload(requestIds,files,request,(key,file)->attachments.upload(id,key,file)));
  }

  @GetMapping("/images/{id}")
  public ResponseEntity<?> image(@PathVariable String id) {
    return ResponseEntity.ok()
        .header("X-Content-Type-Options", "nosniff")
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.parseMediaType(attachments.contentType(id)))
        .body(attachments.content(id));
  }

  @GetMapping("/prints")
  public ApiResponse<?> prints(@RequestParam(defaultValue = "1") long page) {
    return ApiResponse.success("查询成功", service.listPrints(page));
  }

  @GetMapping("/prints/{id}")
  public ApiResponse<?> print(@PathVariable String id) {
    return ApiResponse.success("查询成功", service.getPrint(id));
  }

  @GetMapping("/tasks/{id}/prints")
  public ApiResponse<?> taskPrints(@PathVariable String id) {
    return ApiResponse.success("查询成功", service.taskPrints(id));
  }

  @GetMapping(value = "/prints/{id}/label", produces = "text/html;charset=UTF-8")
  public ResponseEntity<String> label(@PathVariable String id) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(
            "Content-Security-Policy",
            "default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'")
        .body(labels.render(id));
  }

  @PostMapping("/prints/claim")
  public ApiResponse<?> claimNext(@Valid @RequestBody ClaimRequest r) {
    return ApiResponse.success("领取完成", service.claimPrint(null, r.terminal()));
  }

  @PostMapping("/prints/{id}/claim")
  public ApiResponse<?> claim(@PathVariable String id, @Valid @RequestBody ClaimRequest r) {
    return ApiResponse.success("领取完成", service.claimPrint(id, r.terminal()));
  }

  @PostMapping("/prints/{id}/result")
  public ApiResponse<?> printResult(
      @PathVariable String id, @Valid @RequestBody PrintResultRequest r) {
    service.reportPrint(id, r);
    return ApiResponse.success("结果已记录", null);
  }

  @PostMapping("/prints/{id}/reprint")
  public ApiResponse<?> reprint(@PathVariable String id, @Valid @RequestBody ReasonRequest r) {
    return ApiResponse.success("补打任务已创建", service.reprint(id, r.reason()));
  }
}
