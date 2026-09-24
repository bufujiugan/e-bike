package com.bike.server.pi;

import com.bike.server.common.*;
import com.bike.server.production.ProductionFileStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PiExportArchive {
    private final PiExportMapper mapper;
    private final PiMapper pis;
    private final ProductionFileStore files;
    private final ObjectMapper json;
    private final OperationLogMapper logs;
    @org.springframework.beans.factory.annotation.Autowired private PiTemplateMapper templates;
    @org.springframework.beans.factory.annotation.Autowired private CustomerScopeAccess customerScope;
    public PiExportArchive(PiExportMapper mapper,PiMapper pis,ProductionFileStore files,ObjectMapper json,OperationLogMapper logs) {
        this.mapper=mapper;this.pis=pis;this.files=files;this.json=json;this.logs=logs;
    }
    /** Called within the PI confirmation transaction, after status is committed logically, before SQL commit. */
    public void enqueue(PiView pi,String sourceType) {
        if(mapper.get(pi.getId())!=null)return;
        var view=publicSnapshot(pi);
        try {
            if(mapper.enqueue(pi.getId(),json.writeValueAsString(view),sourceType)!=1)throw new IllegalStateException("PI export queue insert failed");
        } catch(IOException error){throw new IllegalStateException("PI export snapshot invalid",error);}
    }
    PiView publicSnapshot(PiView pi) {
        // Copy an explicit allowlist. Never serialize enriched finance, orders, internal release gates or receipts.
        PiView p=new PiView();p.setId(pi.getId());p.setPiNo(pi.getPiNo());p.setRevision(pi.getRevision());
        p.setCustomerCode(pi.getCustomerCode());p.setCustomerName(pi.getCustomerName());p.setTradeType(pi.getTradeType());
        p.setCurrencyCode(pi.getCurrencyCode());p.setStatus("CONFIRMED");p.setPlannedShipDate(pi.getPlannedShipDate());
        p.setSubtotal(pi.getSubtotal());p.setDiscountAmount(pi.getDiscountAmount());p.setTaxAmount(pi.getTaxAmount());
        p.setFreightAmount(pi.getFreightAmount());p.setTotalAmount(pi.getTotalAmount());p.setRemark(pi.getRemark());
        p.setOtherFeeAmount(pi.getOtherFeeAmount());
        p.setCreatedAt(pi.getCreatedAt());p.setItems(pis.selectItems(pi.getId()));
        try {p.setDocument(pi.getDocumentJson()==null?pi.getDocument():json.readValue(pi.getDocumentJson(),PiDocumentSnapshot.class));}
        catch(IOException error){throw new BusinessException("PI_DOCUMENT_INVALID","PI单据快照无法读取");}
        var d=p.getDocument();var template=templates==null||d==null?null:templates.find(d.templateCode(),d.templateVersion());
        p.setDocumentRenderVersion(templates==null?"PI_V2":PiTemplateService.renderVersion(template));
        return p;
    }
    public PiExportJob status(long id){CurrentOperator.requirePermission("PI_EXPORT");return mapper.get(id);}
    public record Retry(@NotNull @PositiveOrZero Long revision,@NotBlank @Size(max=500) String reason) {}
    /** Caller has already checked PI_READ and the company's scope through PiService.get. */
    @Transactional public PiExportJob retry(long id,Retry request) {
        CurrentOperator.requireInternalWebAccess();CurrentOperator.requirePermission("PI_EXPORT");
        if(pis.lockPi(id)==null)throw new BusinessException("PI_NOT_FOUND","PI不存在");
        var pi=pis.selectById(id);var current=mapper.get(id);
        customerScope.requireCustomer(pi.getCustomerId());
        if(!Set.of("CONFIRMED","RELEASED","SUPERSEDED").contains(pi.getStatus()))throw new BusinessException("PI_EXPORT_STATUS_INVALID","仅已发布PI可建立导出归档");
        if(current==null) {
            if(request.revision()!=0)throw conflict();
            enqueue(pi,"LEGACY_SNAPSHOT");
        } else if(mapper.retry(id,request.revision())!=1)throw conflict();
        if(logs.insert("PI_EXPORT",Long.toString(id),"重排PI导出",CurrentOperator.id(),CurrentOperator.username(),
                current==null?null:"{\"status\":\"FAILED\"}","{\"status\":\"PENDING\"}",CurrentOperator.clientIp(),request.reason())!=1)
            throw new BusinessException("PI_EXPORT_RETRY_FAILED","导出重试审计失败");
        return mapper.get(id);
    }
    public byte[] download(long id,String format) {
        var job=mapper.get(id);
        if(job==null||!"READY".equals(job.status()))throw new BusinessException("PI_EXPORT_NOT_READY","导出文件尚未就绪，请查看导出任务");
        String key="pdf".equals(format)?job.pdfKey():job.xlsxKey();String expected="pdf".equals(format)?job.pdfSha256():job.xlsxSha256();
        Long size="pdf".equals(format)?job.pdfSize():job.xlsxSize();
        if(key==null||!key.matches("(?:local:|cos:production/)pi-export-[0-9a-f-]{36}\\.(pdf|xlsx)"))throw corrupt();
        try(var input=files.read(key).getInputStream()) {
            byte[] bytes=input.readNBytes(20*1024*1024+1);
            if(size==null||bytes.length!=size||!sha256(bytes).equals(expected))throw corrupt();
            return bytes;
        }catch(IOException error){throw new BusinessException("PI_EXPORT_FILE_UNAVAILABLE","归档文件读取失败，请检查存储和备份");}
    }
    static String sha256(byte[] value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static BusinessException corrupt(){return new BusinessException("PI_EXPORT_INTEGRITY_FAILED","归档文件完整性校验失败，请从备份恢复，不能重新生成替换原文件");}
    private static BusinessException conflict(){return new BusinessException("PI_EXPORT_CONFLICT","导出任务已变化或正在处理，请刷新后重试");}
}
