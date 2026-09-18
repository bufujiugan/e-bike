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

  public ProductionController(
      ProductionService service,
      ProductionLabelService labels,
      ProductionAttachmentService attachments,
      ProductionExcelService excel) {
    this.service = service;
    this.labels = labels;
    this.attachments = attachments;
    this.excel = excel;
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

  @GetMapping("/inventory")
  public ApiResponse<?> inventory(
      @RequestParam(required = false) Long skuId,
      @RequestParam(required = false) Long warehouseId) {
    return ApiResponse.success("查询成功", service.inventory(skuId, warehouseId));
  }

  @PostMapping("/skus")
  public ApiResponse<?> createSku(@Valid @RequestBody SkuRequest r) {
    service.saveSku(null, r);
    return ApiResponse.success("保存成功", null);
  }

  @GetMapping("/skus/{id}/images")
  public ApiResponse<?> skuImages(@PathVariable long id) {
    return ApiResponse.success("查询成功", attachments.skuImages(id));
  }

  @PostMapping("/skus/{id}/images")
  public ApiResponse<?> uploadSku(@PathVariable long id, @RequestPart MultipartFile file)
      throws java.io.IOException {
    return ApiResponse.success("上传成功", attachments.uploadSku(id, file));
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

  @GetMapping("/tasks")
  public ApiResponse<?> tasks(
      @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "1") long page) {
    return ApiResponse.success("查询成功", service.listTasks(keyword, page));
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
  public ApiResponse<?> quarantinedParts() {
    return ApiResponse.success("查询成功", service.listQuarantinedParts());
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
  public ApiResponse<?> upload(@PathVariable String id, @RequestPart MultipartFile file)
      throws java.io.IOException {
    return ApiResponse.success("上传成功", attachments.upload(id, file));
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
