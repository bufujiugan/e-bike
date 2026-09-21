package com.bike.server.production;

import com.bike.server.common.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ProductionAttachmentService {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(ProductionAttachmentService.class);
  private final ProductionMapper mapper;
  private final ProductionFileStore files;

  public ProductionAttachmentService(ProductionMapper mapper, ProductionFileStore files) {
    this.mapper = mapper;
    this.files = files;
  }

  @Transactional(rollbackFor = Exception.class)
  public Map<String, Object> upload(String issueId, MultipartFile file) throws IOException {
    ProductionAccess.read();
    CurrentOperator.requireAnyPermission("ASSEMBLY_OPERATE", "REWORK_OPERATE");
    ProductionServiceImpl.required(mapper.selectIssue(issueId), "异常不存在");
    return store(issueId, null, file);
  }

  public List<Map<String, Object>> skuImages(long skuId) {
    ProductionAccess.read();
    return mapper.selectSkuAttachments(skuId);
  }

  @Transactional(rollbackFor = Exception.class)
  public Map<String, Object> uploadSku(long skuId, MultipartFile file) throws IOException {
    ProductionAccess.manage();
    ProductionServiceImpl.required(mapper.selectSku(skuId), "SKU不存在");
    return store(null, skuId, file);
  }

  private Map<String, Object> store(String issueId, Long skuId, MultipartFile file)
      throws IOException {
    if (file.isEmpty() || file.getSize() > 10 * 1024 * 1024)
      throw new BusinessException("IMAGE_SIZE_INVALID", "图片大小须在10MB以内");
    String format;
    try (var input = file.getInputStream();
        var stream = ImageIO.createImageInputStream(input)) {
      var readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) throw new BusinessException("IMAGE_INVALID", "请上传真实JPG或PNG图片");
      var reader = readers.next();
      try {
        reader.setInput(stream);
        format = reader.getFormatName().toLowerCase(Locale.ROOT);
        if (!Set.of("jpeg", "png").contains(format)
            || reader.getWidth(0) > 12000
            || reader.getHeight(0) > 12000
            || (long) reader.getWidth(0) * reader.getHeight(0) > 40000000)
          throw new BusinessException("IMAGE_INVALID", "仅支持不超过4000万像素的JPG或PNG");
        // 只读文件头会接受缺少像素数据的截断图片；在尺寸检查后解码确认内容可用。
        reader.addIIOReadWarningListener((source, warning) -> {
          throw new BusinessException("IMAGE_INVALID", "图片数据不完整，请重新上传");
        });
        var decoded = reader.read(0);
        if (decoded == null) throw new BusinessException("IMAGE_INVALID", "图片数据损坏，无法读取");
        decoded.flush();
      } finally {
        reader.dispose();
      }
    } catch (javax.imageio.IIOException error) {
      throw new BusinessException("IMAGE_INVALID", "图片数据损坏，无法读取");
    }
    String id = UUID.randomUUID().toString(),
        key = files.key(id + (format.equals("png") ? ".png" : ".jpg")),
        type = format.equals("png") ? "image/png" : "image/jpeg";
    org.springframework.transaction.support.TransactionSynchronizationManager
        .registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
              @Override
              public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED)
                  try {
                    files.remove(key);
                  } catch (Exception error) {
                    LOG.error("图片事务回滚清理失败 attachmentId={}", id, error);
                  }
              }
            });
    try (var input = file.getInputStream()) {
      files.write(key, input, file.getSize(), type);
    }
    if (skuId == null)
      mapper.insertAttachment(id, issueId, key, type, file.getSize(), CurrentOperator.username());
    else
      mapper.insertSkuAttachment(id, skuId, key, type, file.getSize(), CurrentOperator.username());
    mapper.insertAudit(
        skuId == null ? issueId : String.valueOf(skuId), "上传生产图片", CurrentOperator.username(), id);
    return Map.of("id", id, "contentType", type, "fileSize", file.getSize());
  }

  public org.springframework.core.io.Resource content(String id) {
    ProductionAccess.read();
    var row = ProductionServiceImpl.required(mapper.selectAttachment(id), "图片不存在");
    return files.read(String.valueOf(row.get("objectKey")));
  }

  public String contentType(String id) {
    ProductionAccess.read();
    return String.valueOf(
        ProductionServiceImpl.required(mapper.selectAttachment(id), "图片不存在").get("contentType"));
  }
}
