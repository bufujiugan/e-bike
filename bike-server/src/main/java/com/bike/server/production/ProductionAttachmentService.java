package com.bike.server.production;

import com.bike.server.common.*;
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
  public ProductionAttachmentService(ProductionMapper mapper, ProductionFileStore files, BusinessUploadValidator validator) {
    this.mapper = mapper; this.files = files; this.validator = validator;
  }
  @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
  public Map<String,Object> upload(String issueId, String requestId, MultipartFile file) throws IOException {
    ProductionAccess.read(); CurrentOperator.requireAnyPermission("ASSEMBLY_OPERATE", "REWORK_OPERATE");
    ProductionServiceImpl.required(mapper.selectIssue(issueId), "异常不存在");
    return store(issueId, null, requestId, file);
  }
  public List<Map<String,Object>> skuImages(long skuId) { ProductionAccess.read(); return mapper.selectSkuAttachments(skuId); }
  @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
  public Map<String,Object> uploadSku(long skuId, String requestId, MultipartFile file) throws IOException {
    ProductionAccess.manage(); ProductionServiceImpl.required(mapper.selectSku(skuId), "SKU不存在");
    return store(null, skuId, requestId, file);
  }
  private Map<String,Object> store(String issueId, Long skuId, String requestId, MultipartFile upload) throws IOException {
    if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,64}")) throw new BusinessException("INVALID_PARAMETER", "图片请求标识无效");
    var keys = new ArrayList<String>();
    try (var file = validator.prepareImage(upload)) {
      if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("图片保存必须在事务中执行");
      Object parent = skuId == null ? mapper.lockAttachmentIssue(issueId) : mapper.lockAttachmentSku(skuId);
      if (parent == null) throw new BusinessException("ATTACHMENT_NOT_FOUND", "图片归属对象不存在");
      var previous = mapper.selectAttachmentRequest(issueId, skuId, requestId);
      if (previous != null) {
        if (!Objects.equals(previous.get("sha256"), file.sha256()) || !Objects.equals(previous.get("fileName"), file.fileName())
            || !Objects.equals(previous.get("contentType"), file.contentType()) || ((Number)previous.get("fileSize")).longValue() != file.size())
          throw new BusinessException("IDEMPOTENCY_CONFLICT", "该请求已用于其他生产图片");
        return previous;
      }
      if (mapper.countObjectAttachments(issueId, skuId) >= 50) throw new BusinessException("ATTACHMENT_LIMIT", "每个SKU或生产异常最多50张图片");
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCompletion(int status) { if (status == STATUS_ROLLED_BACK) cleanup(keys); }
      });
      String id = UUID.randomUUID().toString(), key = files.key(id + "." + file.extension()); keys.add(key);
      try (var stream = Files.newInputStream(file.path())) { files.write(key, stream, file.size(), file.contentType(),
          new com.bike.server.files.FileUploadJournal.Context(skuId==null?"PRODUCTION_ISSUE":"SKU_IMAGE",skuId==null?issueId:skuId.toString(),file.fileName(),file.sha256())); }
      BusinessUploadValidator.verifiedBytes(files.read(key),file.size(),file.sha256(),file.contentType());
      int inserted = skuId == null
          ? mapper.insertAttachment(id, issueId, key, file.contentType(), file.size(), file.fileName(), file.sha256(), requestId, CurrentOperator.username())
          : mapper.insertSkuAttachment(id, skuId, key, file.contentType(), file.size(), file.fileName(), file.sha256(), requestId, CurrentOperator.username());
      if (inserted != 1 || mapper.insertAudit(skuId == null ? issueId : String.valueOf(skuId), "上传生产图片", CurrentOperator.username(), id) != 1)
        throw new BusinessException("ATTACHMENT_SAVE_FAILED", "生产图片或审计保存失败");
      return Map.of("id",id,"contentType",file.contentType(),"fileSize",file.size(),"fileName",file.fileName(),"sha256",file.sha256());
    } catch (IOException | RuntimeException error) { cleanup(keys); throw error; }
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
