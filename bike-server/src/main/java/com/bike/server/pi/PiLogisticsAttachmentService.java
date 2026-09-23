package com.bike.server.pi;

import com.bike.server.aftersales.AfterSalesUploadValidator;
import com.bike.server.common.*;
import com.bike.server.production.ProductionFileStore;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PiLogisticsAttachmentService {
    private final PiService pis;
    private final PiLogisticsAttachmentMapper mapper;
    private final ProductionFileStore files;
    private final AfterSalesUploadValidator validator;
    private final OperationLogMapper logs;
    public PiLogisticsAttachmentService(PiService pis,PiLogisticsAttachmentMapper mapper,ProductionFileStore files,AfterSalesUploadValidator validator,OperationLogMapper logs) {
        this.pis=pis;this.mapper=mapper;this.files=files;this.validator=validator;this.logs=logs;
    }
    private void require(long piId,long logisticsId) {
        var pi=pis.get(piId);
        if(pi.getLogistics().stream().noneMatch(row->row.getId()==logisticsId))throw missing();
    }
    public List<PiLogisticsAttachment> list(long piId,long logisticsId){require(piId,logisticsId);return mapper.list(logisticsId,!CurrentOperator.hasRole("WEB_AGENT"));}
    public record Download(PiLogisticsAttachment attachment,byte[] bytes) {}
    public Download download(long piId,long logisticsId,String id) {
        require(piId,logisticsId);var row=mapper.get(logisticsId,id);
        if(row==null||"INTERNAL".equals(row.visibility())&&CurrentOperator.hasRole("WEB_AGENT"))throw missing();
        if(!row.objectKey().matches("(?:local:|cos:production/)pi-logistics-[0-9a-f-]{36}\\.(jpg|jpeg|png|pdf)"))throw missing();
        try(var input=files.read(row.objectKey()).getInputStream()) {
            byte[] bytes=input.readNBytes(10*1024*1024+1);
            if(bytes.length!=row.fileSize()||!PiExportArchive.sha256(bytes).equals(row.sha256()))throw new BusinessException("PI_ATTACHMENT_INTEGRITY_FAILED","物流附件完整性校验失败，请检查备份");
            return new Download(row,bytes);
        }catch(IOException error){throw new BusinessException("PI_ATTACHMENT_UNAVAILABLE","物流附件读取失败");}
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public PiLogisticsAttachment upload(long piId,long logisticsId,String requestId,String visibility,MultipartFile upload)throws IOException {
        CurrentOperator.requireInternalWebAccess();CurrentOperator.requirePermission("PI_LOGISTICS_WRITE");require(piId,logisticsId);
        if(requestId==null||!requestId.matches("[A-Za-z0-9_-]{1,64}")||!Set.of("CUSTOMER","INTERNAL").contains(Objects.toString(visibility,"")))
            throw new BusinessException("INVALID_PARAMETER","物流附件请求标识或可见范围无效");
        if(upload==null||upload.isEmpty()||upload.getSize()>10*1024*1024)throw new BusinessException("ATTACHMENT_SIZE_INVALID","物流附件不能为空且不能超过10MB");
        String name=Objects.toString(upload.getOriginalFilename(),"");String ext=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        if(!Set.of("jpg","jpeg","png","pdf").contains(ext))throw new BusinessException("ATTACHMENT_TYPE_UNSUPPORTED","物流附件仅支持JPG、PNG或PDF");
        var keys=new ArrayList<String>();
        try(var file=validator.prepare(upload)) {
            // Parent status and attachment count share the same row lock as logistics updates; no inventory or PI lock is acquired here.
            String status=mapper.lockLogistics(logisticsId);if(status==null)throw missing();
            var previous=mapper.byRequest(logisticsId,requestId);
            if(previous!=null) {
                if(!previous.sha256().equals(file.sha256())||previous.fileSize()!=file.size()||!previous.fileName().equals(file.fileName())||!previous.visibility().equals(visibility)||!previous.contentType().equals(file.contentType()))
                    throw new BusinessException("IDEMPOTENCY_CONFLICT","此请求已用于其他物流附件");
                return previous;
            }
            if(!Set.of("PLANNED","SHIPPED").contains(status))throw new BusinessException("INVALID_STATUS_TRANSITION","已撤回物流不能追加附件");
            if(mapper.count(logisticsId)>=50)throw new BusinessException("ATTACHMENT_LIMIT","每条物流记录最多50个附件");
            if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Logistics upload requires transaction");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)cleanup(keys);}});
            String id=UUID.randomUUID().toString(),key=files.key("pi-logistics-"+id+"."+file.extension());keys.add(key);
            try(var input=Files.newInputStream(file.path())){files.write(key,input,file.size(),file.contentType());}
            var row=new PiLogisticsAttachment(id,logisticsId,requestId,key,file.fileName(),file.contentType(),file.size(),file.sha256(),visibility,CurrentOperator.id(),null);
            if(mapper.insert(row)!=1||logs.insert("PI",Long.toString(piId),"上传PI物流附件",CurrentOperator.id(),CurrentOperator.username(),null,
                    "{\"attachmentId\":\""+id+"\",\"logisticsId\":"+logisticsId+",\"visibility\":\""+visibility+"\"}",CurrentOperator.clientIp(),null)!=1)
                throw new BusinessException("PI_ATTACHMENT_SAVE_FAILED","物流附件或审计保存失败");
            return mapper.get(logisticsId,id);
        }catch(IOException|RuntimeException error){cleanup(keys);throw error;}
    }
    private void cleanup(List<String> keys){for(String key:List.copyOf(keys))try{files.remove(key);keys.remove(key);}catch(Exception error){org.slf4j.LoggerFactory.getLogger(getClass()).error("PI logistics orphan requires cleanup: {}",key);}}
    private static BusinessException missing(){return new BusinessException("ATTACHMENT_NOT_FOUND","物流记录或附件不存在或无权访问");}
}
