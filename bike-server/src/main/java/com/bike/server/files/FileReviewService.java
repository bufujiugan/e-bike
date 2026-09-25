package com.bike.server.files;

import com.bike.server.common.*;
import com.bike.server.production.ProductionFileStore;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FileReviewService {
    public record Request(String objectKey, Long version, String reason) {}
    public record Page(List<FileUploadRecord> records, long total, int page, int pageSize) {}
    private final FileUploadMapper mapper;
    private final ProductionFileStore files;
    private final OperationLogMapper logs;
    private final TransactionTemplate tx;
    public FileReviewService(FileUploadMapper mapper, ProductionFileStore files, OperationLogMapper logs, PlatformTransactionManager manager) {
        this.mapper=mapper;this.files=files;this.logs=logs;
        tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    private static void access(){CurrentOperator.requireInternalWebAccess();CurrentOperator.requirePermission("FILE_REVIEW_MANAGE");}
    public List<Map<String,Object>> history(String key){access();return mapper.history(key);}
    public Page list(String state,int page,int pageSize) {
        access(); if(state!=null&&state.isBlank())state=null;
        if(state!=null&&!Set.of("PENDING","BOUND","REVIEW","CLEANUP_PENDING","CLEANED").contains(state)
                ||page<1||page>100000||pageSize<1||pageSize>100)throw new BusinessException("INVALID_PARAMETER","核对状态或分页参数无效");
        return new Page(mapper.list(state,(page-1)*pageSize,pageSize),mapper.count(state),page,pageSize);
    }
    public FileUploadRecord reconcile(Request request) {
        access();validate(request);
        return tx.execute(s->{var row=requireLocked(request);check(row,false);var after=mapper.get(row.objectKey());
            if(mapper.review(row.objectKey(),after.state(),request.reason().trim(),CurrentOperator.id())!=1)throw conflict();
            audit(row,"RECONCILE",after.state(),request.reason().trim());return mapper.get(row.objectKey());});
    }
    public FileUploadRecord cleanup(Request request) {
        access();validate(request);
        tx.executeWithoutResult(s->{var row=requireLocked(request);
            if(!Set.of("REVIEW","CLEANUP_PENDING").contains(row.state()))throw new BusinessException("FILE_CLEANUP_FORBIDDEN","仅待核对或待清理的未绑定文件可以清理");
            if(mapper.references(row.objectKey())!=0)throw new BusinessException("FILE_ALREADY_BOUND","文件已被业务引用，禁止清理");
            if(mapper.review(row.objectKey(),"CLEANUP_PENDING",request.reason().trim(),CurrentOperator.id())!=1)throw conflict();
            audit(row,"CLEANUP_REQUEST","CLEANUP_PENDING",request.reason().trim());});
        // Intent and audit commit before external storage deletion; a crash can safely retry this exact key.
        process(request.objectKey());
        return mapper.get(request.objectKey());
    }
    private FileUploadRecord requireLocked(Request request) {
        var row=mapper.lock(request.objectKey());if(row==null)throw new BusinessException("FILE_REVIEW_BUSY","记录不存在或正在上传/核对，请刷新后重试");
        if(row.version()!=request.version())throw conflict();return row;
    }
    private void audit(FileUploadRecord before,String action,String after,String reason) {
        // Do not put filenames, business identifiers or object keys into the general-purpose log feed.
        if(mapper.event(before,action,after,reason,CurrentOperator.id(),CurrentOperator.username())!=1
                || logs.insert("FILE_REVIEW","UPLOAD",action,CurrentOperator.id(),CurrentOperator.username(),
                "{\"state\":\""+before.state()+"\",\"version\":"+before.version()+"}",
                "{\"state\":\""+after+"\"}",CurrentOperator.clientIp(),null)!=1)
            throw new BusinessException("FILE_REVIEW_AUDIT_FAILED","核对审计保存失败，操作已回滚");
    }
    private static void validate(Request r) {
        if(r==null||r.objectKey()==null||r.objectKey().length()>255||r.version()==null||r.version()<0||r.reason()==null||r.reason().isBlank()||r.reason().length()>500)
            throw new BusinessException("INVALID_PARAMETER","须提供有效记录、版本及1至500字核对原因");
    }
    private static BusinessException conflict(){return new BusinessException("FILE_REVIEW_CONFLICT","记录已变化，请刷新后重试");}
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void tick() {
        try {for(String key:mapper.pending())process(key);}
        catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Upload reconciliation deferred; database or storage unavailable");}
    }
    void process(String key) {
        tx.executeWithoutResult(s->{var row=mapper.lock(key);if(row==null)return;
            if(Set.of("PENDING","CLEANUP_PENDING").contains(row.state()))check(row,"CLEANUP_PENDING".equals(row.state()));});
    }
    private void check(FileUploadRecord row,boolean deleteRequested) {
        // Nonlocking reads at READ_COMMITTED avoid taking business locks in reverse order.
        // Every writer must hold this journal lock from before object I/O through binding commit.
        if(mapper.references(row.objectKey())>0) {
            String objectState="UNKNOWN",error="STORAGE_CHECK_FAILED";
            try{objectState=files.exists(row.objectKey())?"PRESENT":"MISSING";error="MISSING".equals(objectState)?"BOUND_OBJECT_MISSING":null;}
            catch(RuntimeException unavailable){/* Keep the durable business pointer protected. */}
            update(row,"BOUND",objectState,error);return;
        }
        if("BOUND".equals(row.state()))throw new BusinessException("FILE_CLEANUP_FORBIDDEN","已绑定历史记录不能通过失败上传清理入口处理");
        if("CLEANED".equals(row.state()))return;
        String nextState,objectState,errorCode;
        try {
            boolean exists=files.exists(row.objectKey());
            if(deleteRequested) {
                if(exists)files.remove(row.objectKey());
                if(files.exists(row.objectKey()))throw new java.io.IOException("Object still exists after removal");
                nextState="CLEANED";objectState="MISSING";errorCode=null;
            } else {nextState="REVIEW";objectState=exists?"PRESENT":"MISSING";errorCode=exists?"UNBOUND_OBJECT":"OBJECT_MISSING";}
        } catch(java.io.IOException|RuntimeException failure) {
            nextState=deleteRequested?"CLEANUP_PENDING":"REVIEW";objectState="UNKNOWN";errorCode=deleteRequested?"CLEANUP_FAILED":"STORAGE_CHECK_FAILED";
        }
        update(row,nextState,objectState,errorCode);
    }
    private void update(FileUploadRecord row,String state,String objectState,String error) {
        if(mapper.checked(row.objectKey(),state,objectState,error)!=1)throw conflict();
    }
}
