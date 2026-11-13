package com.bike.server.pi;

import jakarta.validation.constraints.*;

public record PiTemplateRequest(
        @NotBlank(message="请填写模板编码") @Pattern(regexp="[A-Z0-9][A-Z0-9_-]{0,63}", message="模板编码：须以大写字母或数字开头，仅支持大写字母、数字、下划线和短横线，最多64位，例如 EXPORT_01") String templateCode,
        @NotBlank(message="请填写版本号") @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._-]{0,31}", message="版本号：须以字母或数字开头，仅支持字母、数字、点、下划线和短横线，最多32位，例如 V1.0") String templateVersion,
        @NotBlank(message="请选择贸易类型") @Pattern(regexp="DOMESTIC|EXPORT", message="贸易类型：请选择内销或外销") String tradeType,
        @NotBlank(message="请选择PI版式") @Pattern(regexp="DOMESTIC_V[123]|EXPORT_V[123]|STANDARD_V3|INVOICE_V1", message="PI版式：请选择有效的模板版式") String layoutCode,
        @NotBlank(message="请填写卖方名称") @Size(max=200, message="卖方名称：最多200字") String sellerName,
        @NotBlank(message="请填写卖方地址") @Size(max=1000, message="卖方地址：最多1000字") String sellerAddress,
        @Size(max=300, message="补充联系方式：最多300字") String sellerContact,
        @NotBlank(message="请填写收款银行") @Size(max=200, message="收款银行：最多200字") String bankName,
        @NotBlank(message="请填写收款账号") @Size(max=200, message="收款账号：最多200字") String bankAccount,
        @NotBlank(message="请填写收款单位") @Size(max=200, message="收款单位：最多200字") String beneficiary,
        @NotBlank(message="请填写发布原因") @Size(max=500, message="发布原因：最多500字") String reason,
        @NotBlank(message="请填写卖方国家/地区") @Size(max=100, message="卖方国家/地区：最多100字") String sellerCountry,
        @NotBlank(message="请填写卖方联系人") @Size(max=100, message="卖方联系人：最多100字") String sellerContactName,
        @NotBlank(message="请填写卖方电话") @Size(max=64, message="卖方电话：最多64位") String sellerPhone,
        @NotBlank(message="请填写卖方Email") @Email(message="卖方Email：请填写有效的邮箱地址，例如 sales@example.com") @Size(max=254, message="卖方Email：最多254位") String sellerEmail) {
    public PiTemplateRequest { if(sellerContact==null)sellerContact=""; }
}
