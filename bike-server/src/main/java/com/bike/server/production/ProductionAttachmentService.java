package com.bike.server.production;

import com.bike.server.common.*;
import com.bike.server.files.FileUploadMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.core.io.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ProductionAttachmentService {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ProductionAttachmentService.class);
  private final ProductionMapper mapper;
  private final ProductionFileStore files;
  private final BusinessUploadValidator validator;
  private final FileUploadMapper uploadFiles;
  public ProductionAttachmentService(ProductionMapper mapper, ProductionFileStore files, BusinessUploadValidator validator,
      FileUploadMapper uploadFiles) {
    this.mapper = mapper; this.files = files; this.validator = validator; this.uploadFiles = uploadFiles;
  }
  @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
  public Map<String,Object> upload(String issueId, String requestId, MultipartFile file) throws IOException {
    ProductionAccess.read(); CurrentOperator.requireAnyPermission("ASSEMBLY_OPERATE", "REWORK_OPERATE");
    ProductionServiceImpl.required(mapper.selectIssue(issueId), "异常不存在");
    return store(issueId, null, null, requestId, file);
  }
  public List<Map<String,Object>> skuImages(long skuId) { ProductionAccess.read(); return mapper.selectSkuAttachments(skuId); }
  @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
  public Map<String,Object> uploadSku(long skuId, String requestId, MultipartFile file) throws IOException {
    ProductionAccess.manage(); ProductionServiceImpl.required(mapper.selectSku(skuId), "SKU不存在");
    return store(null, skuId, null, requestId, file);
  }
  @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
  public Map<String,Object> uploadSkuDraft(String draftToken, String requestId, MultipartFile file) throws IOException {
    ProductionAccess.manage(); validateDraftToken(draftToken);
    return store(null, null, draftToken, requestId, file);
  }
  private Map<String,Object> store(String issueId, Long skuId, String draftToken, String requestId, MultipartFile upload) throws IOException {
    if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,64}")) throw new BusinessException("INVALID_PARAMETER", "图片请求标识无效");
    var keys = new ArrayList<String>();
    try (var file = validator.prepareImage(upload)) {
      if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("图片保存必须在事务中执行");
      Map<String,Object> draft = null;
      if (draftToken != null) {
        mapper.insertSkuImageDraft(draftToken, CurrentOperator.username());
        draft = mapper.lockSkuImageDraft(draftToken);
        if (draft == null || !CurrentOperator.username().equals(draft.get("createdBy")))
          throw new BusinessException("PERMISSION_DENIED", "无权使用其他账号的图片草稿");
      } else {
        Object parent = skuId == null ? mapper.lockAttachmentIssue(issueId) : mapper.lockAttachmentSku(skuId);
        if (parent == null) throw new BusinessException("ATTACHMENT_NOT_FOUND", "图片归属对象不存在");
      }
      var previous = draftToken == null
          ? mapper.selectAttachmentRequest(issueId, skuId, requestId)
          : mapper.selectDraftAttachmentRequest(draftToken, requestId);
      if (previous != null) {
        if (!Objects.equals(previous.get("sha256"), file.sha256()) || !Objects.equals(previous.get("fileName"), file.fileName())
            || !Objects.equals(previous.get("contentType"), file.contentType()) || ((Number)previous.get("fileSize")).longValue() != file.size())
          throw new BusinessException("IDEMPOTENCY_CONFLICT", "该请求已用于其他生产图片");
        return previous;
      }
      long attachmentCount = draftToken == null
          ? mapper.countObjectAttachments(issueId, skuId)
          : mapper.countDraftAttachments(draftToken);
      if (attachmentCount >= 50) throw new BusinessException("ATTACHMENT_LIMIT", "每个SKU或生产异常最多50张图片");
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCompletion(int status) { if (status == STATUS_ROLLED_BACK) cleanup(keys); }
      });
      String id = UUID.randomUUID().toString(), key = files.key(id + "." + file.extension()); keys.add(key);
      try (var stream = Files.newInputStream(file.path())) { files.write(key, stream, file.size(), file.contentType(),
          new com.bike.server.files.FileUploadJournal.Context(
              draftToken != null || skuId != null ? "SKU_IMAGE" : "PRODUCTION_ISSUE",
              draftToken != null ? draftToken : skuId == null ? issueId : skuId.toString(), file.fileName(), file.sha256())); }
      BusinessUploadValidator.verifiedBytes(files.read(key),file.size(),file.sha256(),file.contentType());
      int inserted = draftToken != null
          ? mapper.insertDraftAttachment(id, draftToken, key, file.contentType(), file.size(), file.fileName(), file.sha256(), requestId, CurrentOperator.username())
          : skuId == null
              ? mapper.insertAttachment(id, issueId, key, file.contentType(), file.size(), file.fileName(), file.sha256(), requestId, CurrentOperator.username())
              : mapper.insertSkuAttachment(id, skuId, key, file.contentType(), file.size(), file.fileName(), file.sha256(), requestId, CurrentOperator.username());
      if (draftToken != null) mapper.touchSkuImageDraft(draftToken);
      String businessId = draftToken != null ? draftToken : skuId == null ? issueId : String.valueOf(skuId);
      if (inserted != 1 || mapper.insertAudit(businessId, "上传生产图片", CurrentOperator.username(), id) != 1)
        throw new BusinessException("ATTACHMENT_SAVE_FAILED", "生产图片或审计保存失败");
      return Map.of("id",id,"contentType",file.contentType(),"fileSize",file.size(),"fileName",file.fileName(),"sha256",file.sha256());
    } catch (IOException | RuntimeException error) { cleanup(keys); throw error; }
  }
  @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
  public void discardSkuDraft(String draftToken) {
    ProductionAccess.manage(); discardSkuDraft(draftToken, CurrentOperator.username());
  }
  @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
  public void cleanupStaleSkuDraft(String draftToken) { discardSkuDraft(draftToken, null); }
  private void discardSkuDraft(String draftToken, String requiredOwner) {
    validateDraftToken(draftToken);
    var draft = mapper.lockSkuImageDraft(draftToken);
    if (draft == null) return;
    if (requiredOwner != null && !requiredOwner.equals(draft.get("createdBy")))
      throw new BusinessException("PERMISSION_DENIED", "无权清理其他账号的图片草稿");
    var attachments = mapper.selectDraftAttachments(draftToken);
    if (mapper.deleteDraftAttachments(draftToken) != attachments.size() || mapper.deleteSkuImageDraft(draftToken) != 1)
      throw new BusinessException("ATTACHMENT_DRAFT_CLEANUP_FAILED", "图片草稿清理失败，请稍后重试");
    for (var attachment : attachments) {
      String key = String.valueOf(attachment.get("objectKey"));
      if (uploadFiles.checked(key, "CLEANUP_PENDING", "PRESENT", null) != 1)
        throw new BusinessException("ATTACHMENT_DRAFT_CLEANUP_FAILED", "图片清理任务登记失败，请稍后重试");
    }
  }
  private static void validateDraftToken(String draftToken) {
    if (draftToken == null || !draftToken.matches("[A-Za-z0-9_-]{1,64}"))
      throw new BusinessException("INVALID_PARAMETER", "图片草稿标识无效");
  }
  public Resource content(String id) {
    ProductionAccess.read();
    var row = ProductionServiceImpl.required(mapper.selectAttachment(id), "图片不存在");
    String key = Objects.toString(row.get("objectKey"), ""), type = Objects.toString(row.get("contentType"), "");
    if (!key.matches("(?:local:|cos:production/)[0-9a-f-]{36}\\.(jpg|jpeg|png|webp)")) throw new BusinessException("ATTACHMENT_STORAGE_INVALID", "生产图片存储信息不合法");
    long size = ((Number)row.get("fileSize")).longValue();
    if (row.get("sha256") != null) return new ByteArrayResource(BusinessUploadValidator.verifiedBytes(files.read(key),size,String.valueOf(row.get("sha256")),type));
    // Legacy files have no original digest. Do not invent a trusted historical hash.
    if (size <= 0 || size > BusinessUploadValidator.IMAGE_LIMIT || !Set.of("image/jpeg","image/png","image/webp").contains(type))
      throw new BusinessException("PI_ATTACHMENT_INTEGRITY_FAILED", "历史图片元数据不合法");
    try (var stream = files.read(key).getInputStream()) {
      byte[] bytes = stream.readNBytes((int)size + 1);
      if (bytes.length != size) throw new BusinessException("PI_ATTACHMENT_INTEGRITY_FAILED", "历史图片长度不一致");
      return new ByteArrayResource(bytes);
    } catch (IOException error) { throw new BusinessException("IMAGE_NOT_FOUND", "历史图片读取失败"); }
  }
  public String contentType(String id) {
    ProductionAccess.read(); return String.valueOf(ProductionServiceImpl.required(mapper.selectAttachment(id), "图片不存在").get("contentType"));
  }
  private void cleanup(List<String> keys) {
    for (String key : List.copyOf(keys)) try { files.remove(key); keys.remove(key); }
    catch (Exception error) { LOG.error("生产图片事务回滚清理失败 key={}", key, error); }
  }
}
