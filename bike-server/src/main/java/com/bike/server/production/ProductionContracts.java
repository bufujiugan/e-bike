package com.bike.server.production;

import jakarta.validation.constraints.*;

public final class ProductionContracts {
  private ProductionContracts() {}

  public record SkuRequest(
      @NotNull Long modelId,
      @NotBlank @Size(max = 64) String skuCode,
      @NotBlank @Size(max = 100) String productName,
      @Size(max = 100) String specification,
      @Size(max = 32) String ean,
      @Size(max = 32) String upc,
      @NotNull Long templateId,
      boolean enabled) {}

  public record TemplateRequest(
      @NotBlank @Size(max = 100) String name,
      @Min(60) @Max(210) int widthMm,
      @Min(60) @Max(297) int heightMm,
      @NotNull @Pattern(regexp = "STANDARD|COMPACT") String layout,
      @NotBlank @Size(max = 100) String heading) {}

  public record TaskRequest(
      @NotBlank @Pattern(regexp = "[a-zA-Z0-9-]{16,64}") String requestId,
      @NotNull Long skuId,
      @Size(max = 64) String batchNo) {}

  public record ScanRequest(
      @NotBlank(message = "扫码编码不能为空") @Size(max = 255, message = "扫码编码不能超过255个字符") String code,
      @Pattern(regexp = "FRAME|MOTOR|BATTERY|DISPLAY", message = "编码类型必须为车架、电机、电池或仪表") String expectedType) {}

  public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}

  public record IssueRequest(
      @NotBlank @Size(max = 200) String code, @NotBlank @Size(max = 500) String reason) {}

  public record PrintResultRequest(
      @NotNull @Pattern(regexp = "SUBMITTED|FAILED|UNKNOWN") String status,
      @Size(max = 500) String message) {}

  public record ClaimRequest(@NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{1,64}") String terminal) {}

  public record PrintSnapshot(
      String frameSn,
      String motorSn,
      String batterySn,
      String displaySn,
      String qrCode,
      String skuCode,
      String modelName,
      String productName,
      String specification,
      String ean,
      String upc,
      String batchNo,
      String templateName,
      int templateVersion,
      int widthMm,
      int heightMm,
      String layout,
      String heading) {}
}
