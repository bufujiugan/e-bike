package com.bike.server.pi;

import com.bike.server.common.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PiTemplateService {
    private final PiTemplateMapper mapper;
    private final OperationLogMapper logs;
    private final ObjectMapper json;
    private final Validator validator;
    public PiTemplateService(PiTemplateMapper mapper,OperationLogMapper logs,ObjectMapper json,Validator validator) {
        this.mapper=mapper;this.logs=logs;this.json=json;this.validator=validator;
    }
    public PageResponse<PiTemplate> list(String tradeType,long page,long pageSize) {
        CurrentOperator.requireInternalWebAccess();CurrentOperator.requireAnyPermission("PI_WRITE","PI_TEMPLATE_MANAGE");
        if(tradeType!=null&&!Set.of("DOMESTIC","EXPORT").contains(tradeType))throw invalid();
        long p=Math.min(10_000_000,Math.max(1,page)),s=Math.min(100,Math.max(1,pageSize));
        return new PageResponse<>(mapper.list(tradeType,(p-1)*s,s),p,s,mapper.count(tradeType));
    }
    @Transactional
    public PiTemplate publish(PiTemplateRequest request) {
        CurrentOperator.requireInternalWebAccess();CurrentOperator.requirePermission("PI_TEMPLATE_MANAGE");
        if(request==null||!validator.validate(request).isEmpty()||!isSelectableLayout(request.layoutCode())
                ||request.layoutCode().startsWith("DOMESTIC_")&&!"DOMESTIC".equals(request.tradeType())
                ||request.layoutCode().startsWith("EXPORT_")&&!"EXPORT".equals(request.tradeType()))throw invalid();
        try {
            if(mapper.publish(request,CurrentOperator.id())!=1)throw new BusinessException("PI_TEMPLATE_SAVE_FAILED","模板发布失败");
        } catch(DuplicateKeyException error) { throw new BusinessException("PI_TEMPLATE_EXISTS","此模板版本已发布，请使用新版本号"); }
        var result=mapper.find(request.templateCode(),request.templateVersion());
        try {
            if(logs.insert("PI_TEMPLATE",Long.toString(result.id()),"发布PI模板",CurrentOperator.id(),CurrentOperator.username(),null,
                    json.writeValueAsString(result),CurrentOperator.clientIp(),request.reason())!=1)
                throw new BusinessException("PI_TEMPLATE_SAVE_FAILED","模板发布审计失败");
        } catch(JsonProcessingException error) { throw new IllegalStateException(error); }
        return result;
    }
    /** Re-checked at confirmation, including drafts created before controlled templates existed. */
    public void requirePublished(PiDocumentSnapshot d,String tradeType) {
        PiTemplate t=d==null?null:mapper.find(d.templateCode(),d.templateVersion());
        if(t==null)throw new BusinessException("PI_TEMPLATE_NOT_PUBLISHED","请先选择已发布的PI模板版本");
        if(!Objects.equals(t.tradeType(),tradeType))throw new BusinessException("PI_TEMPLATE_TRADE_MISMATCH","模板与内外销类型不一致");
        if(!isSelectableLayout(t.layoutCode()))throw new BusinessException("PI_TEMPLATE_LEGACY_READ_ONLY","历史模板仅供读取；请发布并选择含完整卖方资料的新版本");
        if(!Objects.equals(t.sellerName(),d.sellerName())||!Objects.equals(t.sellerAddress(),d.sellerAddress())
                ||!Objects.equals(Objects.requireNonNullElse(t.sellerContact(),""),Objects.requireNonNullElse(d.sellerContact(),""))||(d.business()==null&&!Objects.equals(t.bankName(),d.bankName()))
                ||(d.business()==null&&(!Objects.equals(t.bankAccount(),d.bankAccount())||!Objects.equals(t.beneficiary(),d.beneficiary())))
                ||!Objects.equals(t.sellerCountry(),d.sellerCountry())||!Objects.equals(t.sellerContactName(),d.sellerContactName())
                ||!Objects.equals(t.sellerPhone(),d.sellerPhone())||!Objects.equals(t.sellerEmail(),d.sellerEmail())
                // 卖方登记号与 VAT 随模板固定；银行附加资料与银行账户一样在有业务条款时可按本单调整。
                ||!sameOptional(t.sellerRegistrationNo(),d.sellerRegistrationNo())||!sameOptional(t.sellerVatNo(),d.sellerVatNo())
                ||(d.business()==null&&(!sameOptional(t.bankAddress(),d.bankAddress())||!sameOptional(t.swiftCode(),d.swiftCode())
                    ||!sameOptional(t.bankCode(),d.bankCode())||!sameOptional(t.branchCode(),d.branchCode()))))
            throw new BusinessException("PI_TEMPLATE_SNAPSHOT_MISMATCH","卖方和银行资料须使用已发布模板；变更请发布新版本后重新选择");
    }
    public void selectRenderer(PiView pi) {
        var d=pi.getDocument();
        pi.setDocumentRenderVersion(renderVersion(d==null?null:mapper.find(d.templateCode(),d.templateVersion()), d, pi.getTradeType()));
    }
    static String renderVersion(PiTemplate template, PiDocumentSnapshot document) {
        return renderVersion(template, document, template == null ? null : template.tradeType());
    }
    /**
     * 单据版式由销售类型决定，已发布模板只提供卖方和银行资料：
     * 外销（销售订单 → 外销）使用 260728英国builtPI 原件底板 PI_V6；欧洲销售与交付使用 France_1104_PI 的 PI_EUROPE_V2；
     * 没有销售入口标识的历史 PI 按 SalesType.ofPi 归类，国内内销输出购销合同。仅用于草稿预览和新归档，已入队文件保留原版本。
     */
    static String renderVersion(PiTemplate template, PiDocumentSnapshot document, String tradeType) {
        if (document != null && document.business() != null) {
            var business = document.business();
            return switch (com.bike.server.order.SalesType.ofPi(tradeType, business.salesChannel(), business.domesticShipment())) {
                case com.bike.server.order.SalesType.EUROPE -> PiEuropeDocumentRendererV2.VERSION;
                case com.bike.server.order.SalesType.EXPORT -> PiUkInvoiceRenderer.VERSION;
                default -> PiDomesticContractRenderer.VERSION;
            };
        }
        return renderVersion(template);
    }
    static String renderVersion(PiTemplate template) {
        if(template==null)return "PI_V1";
        return switch(template.layoutCode()) {
            case "DOMESTIC_V1","EXPORT_V1" -> "PI_V1";
            case "DOMESTIC_V2","EXPORT_V2" -> "PI_V2";
            // 新导出使用同步的 Excel 与 PDF 发票版式；已入队快照仍按原版本生成。
            case "DOMESTIC_V3","EXPORT_V3" -> "PI_V5";
            case "STANDARD_V3" -> "PI_V3";
            case "INVOICE_V1" -> "PI_V5";
            default -> throw new BusinessException("PI_TEMPLATE_INVALID","无法识别历史模板版式，请核对模板数据");
        };
    }
    static boolean isSelectableLayout(String layout) {
        return layout != null && Set.of("DOMESTIC_V3","EXPORT_V3","STANDARD_V3","INVOICE_V1").contains(layout);
    }
    private static boolean sameOptional(String expected,String actual){
        return Objects.equals(expected==null||expected.isBlank()?null:expected.trim(),actual==null||actual.isBlank()?null:actual.trim());
    }
    private static BusinessException invalid(){return new BusinessException("PI_TEMPLATE_INVALID","须填写完整卖方国家、联系人、电话、Email及银行资料，并选择可用版式");}
}
