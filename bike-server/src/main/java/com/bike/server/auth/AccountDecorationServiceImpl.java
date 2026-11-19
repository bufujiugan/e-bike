package com.bike.server.auth;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AccountDecorationServiceImpl implements AccountDecorationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountDecorationServiceImpl.class);
    private static final int MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DIMENSION = 4096;
    private static final int DECORATION_SIZE = 384;
    private final Path decorationRoot;

    public AccountDecorationServiceImpl(@Value("${bike.production.file-root:./storage/production}") String directory) {
        decorationRoot = Path.of(directory).toAbsolutePath().normalize().resolve("decorations");
    }

    @Override
    public AccountDecorationView getCurrentDecoration() {
        long userId = requireCurrentUserId();
        try {
            return decorationView(Files.readAllBytes(decorationPath(userId)));
        } catch (NoSuchFileException exception) {
            // 尚未设置装饰图片或并发恢复默认时，返回默认装饰图片。
            return new AccountDecorationView(null);
        } catch (IOException exception) {
            throw storageFailure("读取", userId, exception);
        }
    }

    @Override
    public AccountDecorationView updateCurrentDecoration(MultipartFile file) {
        long userId = requireCurrentUserId();
        byte[] thumbnail = createThumbnail(file);
        Path stagedDecoration = null;
        try {
            Files.createDirectories(decorationRoot);
            stagedDecoration = Files.createTempFile(decorationRoot, "decoration-" + userId + "-", ".png");
            Files.write(stagedDecoration, thumbnail);
            // 同目录原子替换：失败保留旧装饰图片，并发保存以最后成功替换者为准，不产生半张图片。
            Files.move(stagedDecoration, decorationPath(userId), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("装饰图片更新成功 userId={}", userId);
            return decorationView(thumbnail);
        } catch (IOException exception) {
            throw storageFailure("保存", userId, exception);
        } finally {
            if (stagedDecoration != null) {
                try {
                    Files.deleteIfExists(stagedDecoration);
                } catch (IOException exception) {
                    LOGGER.warn("装饰图片暂存文件清理失败 userId={}，保留文件供后续检查", userId, exception);
                }
            }
        }
    }

    @Override
    public AccountDecorationView resetCurrentDecoration() {
        long userId = requireCurrentUserId();
        try {
            Files.deleteIfExists(decorationPath(userId));
            LOGGER.info("装饰图片恢复默认成功 userId={}", userId);
            return new AccountDecorationView(null);
        } catch (IOException exception) {
            throw storageFailure("恢复默认", userId, exception);
        }
    }

    private long requireCurrentUserId() {
        Long userId = CurrentOperator.id();
        if (userId == null || userId <= 0) throw new BusinessException("UNAUTHORIZED", "请先登录");
        return userId;
    }

    private Path decorationPath(long userId) {
        // 路径仅使用认证上下文的数字 ID，不接受客户端账号或文件名。
        return decorationRoot.resolve("user-" + userId + ".png");
    }

    private byte[] createThumbnail(MultipartFile file) {
        if (file == null || file.isEmpty()) throw invalidImage();
        if (file.getSize() > MAX_UPLOAD_BYTES) throw new BusinessException("DECORATION_TOO_LARGE", "装饰图片文件不能超过 2 MB");
        try (InputStream upload = file.getInputStream()) {
            byte[] content = upload.readNBytes(MAX_UPLOAD_BYTES + 1);
            if (content.length > MAX_UPLOAD_BYTES) throw new BusinessException("DECORATION_TOO_LARGE", "装饰图片文件不能超过 2 MB");
            try (var imageInput = new MemoryCacheImageInputStream(new ByteArrayInputStream(content))) {
                var readers = ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) throw invalidImage();
                var reader = readers.next();
                try {
                    if (!Set.of("png", "jpeg", "jpg", "webp").contains(reader.getFormatName().toLowerCase(Locale.ROOT))) throw invalidImage();
                    reader.setInput(imageInput, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
                        throw new BusinessException("DECORATION_DIMENSIONS_INVALID", "图片宽高均不能超过 4096 像素");
                    }
                    BufferedImage original = reader.read(0);
                    if (original == null) throw invalidImage();
                    // 等比例缩小并保留透明背景，不裁切用户图片，也不放大低分辨率原图。
                    double scale = Math.min(1.0, (double) DECORATION_SIZE / Math.max(width, height));
                    int targetWidth = Math.max(1, (int) Math.round(width * scale));
                    int targetHeight = Math.max(1, (int) Math.round(height * scale));
                    BufferedImage thumbnail = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
                    var graphics = thumbnail.createGraphics();
                    try {
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        graphics.drawImage(original, 0, 0, targetWidth, targetHeight, null);
                    } finally {
                        graphics.dispose();
                    }
                    var encoded = new ByteArrayOutputStream();
                    if (!ImageIO.write(thumbnail, "png", encoded)) throw new IOException("PNG encoder unavailable");
                    return encoded.toByteArray();
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException exception) {
            LOGGER.warn("装饰图片解析失败 userId={}，拒绝本次上传", CurrentOperator.id(), exception);
            throw invalidImage();
        }
    }

    private AccountDecorationView decorationView(byte[] image) {
        return new AccountDecorationView("data:image/png;base64," + Base64.getEncoder().encodeToString(image));
    }

    private BusinessException invalidImage() {
        return new BusinessException("DECORATION_IMAGE_INVALID", "请选择有效的 JPG、PNG 或 WebP 图片");
    }

    private IllegalStateException storageFailure(String operation, long userId, IOException exception) {
        return new IllegalStateException("装饰图片" + operation + "失败 userId=" + userId, exception);
    }
}
