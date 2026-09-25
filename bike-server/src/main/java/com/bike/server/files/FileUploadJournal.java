package com.bike.server.files;

import com.bike.server.common.*;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;

/** Intent survives rollback. The binding transaction owns the journal row lock until it commits. */
@Component
public class FileUploadJournal {
    public record Context(String purpose, String businessId, String fileName, String sha256) {}
    private final FileUploadMapper mapper;
    private final FileUploadIntentStore intents;
    public FileUploadJournal(FileUploadMapper mapper, FileUploadIntentStore intents) {
        this.mapper=mapper;this.intents=intents;
    }
    public void begin(String key, Context context, long size, String type) {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Tracked upload requires a binding transaction");
        if(context==null || !Set.of("PRODUCTION_ISSUE","SKU_IMAGE","PI_CONFIRMATION","PI_RECEIPT","PI_LOGISTICS","AFTER_SALES","PI_EXPORT").contains(context.purpose())
                || context.businessId()==null || context.businessId().isBlank() || context.businessId().length()>64
                || context.fileName()==null || context.fileName().length()>255 || context.sha256()==null || !context.sha256().matches("[0-9a-fA-F]{64}")
                || key==null || !key.matches("(?:local:|cos:production/)[A-Za-z0-9-]+\\.[a-z0-9]+") || size<=0)
            throw new IllegalArgumentException("Invalid upload journal context");
        Long user=CurrentOperator.id(); String name=CurrentOperator.username();
        intents.begin(key,context,size,type,user,name);
        var row=mapper.lock(key);
        if(row==null || !"PENDING".equals(row.state()))throw new BusinessException("FILE_UPLOAD_BUSY","上传记录正在核对，请重试");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                if(mapper.references(key)==0 || mapper.bound(key)!=1)
                    throw new BusinessException("FILE_BINDING_FAILED","文件未完成业务绑定，已进入待核对清单");
            }
            @Override public void afterCompletion(int status) {
                if(status==STATUS_COMMITTED)return;
                try { intents.failed(key); }
                catch(RuntimeException error) { org.slf4j.LoggerFactory.getLogger(FileUploadJournal.class).warn("Upload journal reconciliation deferred for {}",key); }
            }
        });
    }
}
