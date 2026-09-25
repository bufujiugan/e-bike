package com.bike.server.production;

import com.bike.server.common.BusinessException;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.region.Region;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.stereotype.Component;

/** 存储键携带后端前缀，切换存储后仍能读取此前保存的本地文件。 */
@Component
public class ProductionFileStore implements AutoCloseable {
  @org.springframework.beans.factory.annotation.Autowired private com.bike.server.files.FileUploadJournal uploadJournal;
  private final Path root;
  private final COSClient cos;
  private final String bucket;
  private final boolean useCos;

  public ProductionFileStore(
      @Value("${bike.production.file-root:./storage/production}") String directory,
      @Value("${bike.production.storage:local}") String storage,
      @Value("${bike.production.cos.region:}") String region,
      @Value("${bike.production.cos.bucket:}") String bucket,
      @Value("${bike.production.cos.secret-id:}") String secretId,
      @Value("${bike.production.cos.secret-key:}") String secretKey) {
    root = Path.of(directory).toAbsolutePath().normalize();
    this.bucket = bucket;
    if (!java.util.Set.of("local", "cos").contains(storage))
      throw new IllegalStateException("图片存储配置只支持local或cos");
    useCos = "cos".equals(storage);
    if (useCos
        && (region.isBlank() || bucket.isBlank() || secretId.isBlank() || secretKey.isBlank()))
      throw new IllegalStateException("COS配置不完整");
    if (!region.isBlank() && !bucket.isBlank() && !secretId.isBlank() && !secretKey.isBlank()) {
      var config = new ClientConfig(new Region(region));
      config.setHttpProtocol(HttpProtocol.https);
      config.setConnectionTimeout(10000);
      config.setSocketTimeout(30000);
      cos = new COSClient(new BasicCOSCredentials(secretId, secretKey), config);
    } else cos = null;
  }

  public String key(String filename) {
    return useCos ? "cos:production/" + filename : "local:" + filename;
  }

  public void write(String key, InputStream input, long size, String type) throws IOException {
    if (key.startsWith("cos:")) {
      var metadata = new ObjectMetadata();
      metadata.setContentLength(size);
      metadata.setContentType(type);
      cos.putObject(bucket, key.substring(4), input, metadata);
    } else {
      Files.createDirectories(root);
      Files.copy(input, localPath(key));
    }
  }

  /** Spring-managed writes require a durable intent; direct low-level instances are used by isolated storage tests. */
  public void write(String key, InputStream input, long size, String type,
      com.bike.server.files.FileUploadJournal.Context context) throws IOException {
    if (uploadJournal != null) uploadJournal.begin(key, context, size, type);
    write(key, input, size, type);
  }

  public boolean exists(String key) {
    if (key.startsWith("cos:")) {
      if (cos == null) throw new BusinessException("STORAGE_UNAVAILABLE", "原COS存储配置不可用");
      return cos.doesObjectExist(bucket, key.substring(4));
    }
    return Files.isRegularFile(localPath(key));
  }

  public void verify(String key, long expectedSize, String expectedHash) throws IOException {
    try (var input = read(key).getInputStream()) {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[65536]; long size = 0; int count;
      while ((count = input.read(buffer)) != -1) {
        size += count;
        if (size > expectedSize) throw new IOException("Stored file size differs from upload");
        digest.update(buffer, 0, count);
      }
      if (size != expectedSize || !java.util.HexFormat.of().formatHex(digest.digest()).equals(expectedHash))
        throw new IOException("Stored file digest differs from upload");
    } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }

  public void remove(String key) throws IOException {
    if (key.startsWith("cos:")) cos.deleteObject(bucket, key.substring(4));
    else Files.deleteIfExists(localPath(key));
  }

  public Resource read(String key) {
    if (key.startsWith("cos:")) {
      if (cos == null) throw new BusinessException("STORAGE_UNAVAILABLE", "请配置原COS存储桶后访问图片");
      return new InputStreamResource(cos.getObject(bucket, key.substring(4)).getObjectContent());
    }
    var path = localPath(key);
    if (!Files.isRegularFile(path)) throw new BusinessException("IMAGE_NOT_FOUND", "图片文件不存在，请检查存储");
    return new FileSystemResource(path);
  }

  private Path localPath(String key) {
    var path = root.resolve(key.startsWith("local:") ? key.substring(6) : key).normalize();
    if (!path.startsWith(root)) throw new BusinessException("IMAGE_NOT_FOUND", "图片路径无效");
    return path;
  }

  @PreDestroy
  public void close() {
    if (cos != null) cos.shutdown();
  }
}
