package com.bike.server.pi;

import com.bike.server.common.*;
import com.bike.server.auth.UserAuthRecord;
import com.bike.server.production.ProductionFileStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PiReceiptAttachmentService {
    @org.springframework.beans.factory.annotation.Autowired private com.bike.server.common.CustomerScopeAccess customerScope;
    private static final Logger LOG = LoggerFactory.getLogger(PiReceiptAttachmentService.class);
    @org.springframework.beans.factory.annotation.Autowired private PiMapper pis;
    private final PiReceiptAttachmentMapper mapper;
    private final ProductionFileStore files;
    private final PiReceiptFileValidator validator;
    private final OperationLogMapper logs;
    private final ObjectMapper json;
    public PiReceiptAttachmentService(PiReceiptAttachmentMapper mapper, ProductionFileStore files,
            PiReceiptFileValidator validator, OperationLogMapper logs, ObjectMapper json) {
        this.mapper = mapper; this.files = files; this.validator = validator; this.logs = logs; this.json = json;
    }

    public List<PiReceiptAttachmentView> list(long piId, long paymentId) {
        requireRead(); requirePiScope(piId); requireReceipt(mapper.selectReceipt(piId, paymentId));
        return mapper.selectAttachments(paymentId);
    }

    public Download download(long piId, long paymentId, String attachmentId) {
        requireRead(); requirePiScope(piId); requireReceipt(mapper.selectReceipt(piId, paymentId));
        var attachment = mapper.selectStored(paymentId, attachmentId);
        if (attachment == null || !Objects.equals(attachment.paymentId(), paymentId)) throw missing();
        if (attachment.objectKey() == null || !attachment.objectKey().matches("(?:local:|cos:production/)pi-receipt-[0-9a-f-]{36}\\.(?:jpg|jpeg|png|webp|pdf|xlsx)"))
            throw new BusinessException("ATTACHMENT_STORAGE_INVALID", "凭证存储信息不合法");
        byte[] bytes = BusinessUploadValidator.verifiedBytes(files.read(attachment.objectKey()), attachment.fileSize(), attachment.sha256(), attachment.contentType());
        return new Download(attachment.fileName(), attachment.contentType(), bytes.length, new org.springframework.core.io.ByteArrayResource(bytes));
    }

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public PiReceiptAttachmentView upload(long piId, long paymentId, String requestId, MultipartFile upload) throws IOException {
        requireRead(); requirePiScope(piId); CurrentOperator.requirePermission("PI_PAYMENT_CONFIRM");
        requireReceipt(mapper.selectReceipt(piId, paymentId));
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,64}"))
            throw new BusinessException("INVALID_PARAMETER", "凭证请求标识须为1至64位字母、数字、横线或下划线");
        var keys = new ArrayList<String>();
        try (var file = validator.prepare(upload)) {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("凭证上传必须在事务中执行");
            // Existing payment operations lock PI before receipt. Never acquire an inventory lock here.
            if (mapper.lockPi(piId) == null) throw missing();
            var receipt = mapper.lockReceipt(piId, paymentId);
            requireReceipt(receipt);
            var previous = mapper.selectByRequest(paymentId, requestId);
            if (previous != null) {
                if (!Objects.equals(previous.sha256(), file.sha256()) || previous.fileSize() != file.size()
                        || !Objects.equals(previous.fileName(), file.fileName()) || !Objects.equals(previous.contentType(), file.contentType()))
                    throw new BusinessException("IDEMPOTENCY_CONFLICT", "该凭证请求已用于其他文件，请核对到账记录后重新选择文件");
                return previous.view();
            }
            requireConfirmed(receipt);
            if (mapper.count(paymentId) >= 50) throw new BusinessException("ATTACHMENT_LIMIT", "每笔到账记录最多50个凭证");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { if (status == STATUS_ROLLED_BACK) cleanup(keys); }
            });
            String id = UUID.randomUUID().toString();
            String key = files.key("pi-receipt-" + id + "." + file.extension());
            keys.add(key); // A failed write may already have created a partial object.
            try (var input = Files.newInputStream(file.path())) { files.write(key, input, file.size(), file.contentType(),
                    new com.bike.server.files.FileUploadJournal.Context("PI_RECEIPT",Long.toString(paymentId),file.fileName(),file.sha256())); }
            BusinessUploadValidator.verifiedBytes(files.read(key),file.size(),file.sha256(),file.contentType());
            var row = new PiReceiptAttachmentMapper.Stored(id, paymentId, requestId, key, file.fileName(),
                    file.contentType(), file.size(), file.sha256(), CurrentOperator.id(), null);
            if (mapper.insert(row) != 1) throw new BusinessException("PI_ATTACHMENT_SAVE_FAILED", "凭证记录保存失败");
            var saved = mapper.selectStored(paymentId, id);
            if (saved == null) throw new BusinessException("PI_ATTACHMENT_SAVE_FAILED", "凭证保存后无法读取");
            if (logs.insert("PI", Long.toString(piId), "上传 PI 收款凭证", CurrentOperator.id(), CurrentOperator.username(),
                    null, json.writeValueAsString(saved.view()), CurrentOperator.clientIp(), null) != 1)
                throw new BusinessException("PI_ATTACHMENT_AUDIT_FAILED", "凭证操作历史保存失败");
            return saved.view();
        } catch (IOException | RuntimeException error) {
            cleanup(keys); throw error;
        }
    }

    private void requirePiScope(long piId) {
        var pi=pis.selectById(piId);if(pi==null)throw missing();
        customerScope.requireCustomer(pi.getCustomerId());
    }

    private static void requireRead() {
        CurrentOperator.requireInternalWebAccess(); CurrentOperator.requirePermission("PI_READ");
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getDetails() instanceof UserAuthRecord user
                && Set.of("AGENT", "PDA_OPERATOR").contains(user.userType()))
            throw new BusinessException("PERMISSION_DENIED", "代理商及PDA账号不能访问收款凭证");
        if (!FinancialAuditAccess.allowed()) throw new BusinessException("PERMISSION_DENIED", "当前账号无权查看收款凭证");
    }
    private static void requireReceipt(PiReceiptAttachmentMapper.Receipt receipt) { if (receipt == null) throw missing(); }
    private static void requireConfirmed(PiReceiptAttachmentMapper.Receipt receipt) {
        requireReceipt(receipt);
        if (!"CONFIRMED".equals(receipt.status())) throw new BusinessException("INVALID_STATUS_TRANSITION", "仅已确认到账记录可以补传凭证");
    }
    private static BusinessException missing() { return new BusinessException("ATTACHMENT_NOT_FOUND", "到账记录或凭证不存在"); }
    private void cleanup(List<String> keys) {
        for (String key : List.copyOf(keys)) try { files.remove(key); keys.remove(key); }
        catch (Exception error) { LOG.error("PI凭证事务回滚后对象清理失败 key={}", key, error); }
    }
    public record Download(String fileName, String contentType, long size, Resource resource) {}
}
