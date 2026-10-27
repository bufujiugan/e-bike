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
public class AccountAvatarServiceImpl implements AccountAvatarService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountAvatarServiceImpl.class);
    private static final int MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DIMENSION = 4096;
    private static final int AVATAR_SIZE = 256;
    private final Path avatarRoot;

    public AccountAvatarServiceImpl(@Value("${bike.production.file-root:./storage/production}") String directory) {
        avatarRoot = Path.of(directory).toAbsolutePath().normalize().resolve("avatars");
    }

    @Override
    public AccountAvatarView getCurrentAvatar() {
        long userId = requireCurrentUserId();
        try {
            return avatarView(Files.readAllBytes(avatarPath(userId)));
        } catch (NoSuchFileException exception) {
            // 尚未设置头像或并发恢复默认时，返回默认字母头像。
            return new AccountAvatarView(null);
        } catch (IOException exception) {
            throw storageFailure("读取", userId, exception);
        }
    }

    @Override
    public AccountAvatarView updateCurrentAvatar(MultipartFile file) {
        long userId = requireCurrentUserId();
        byte[] thumbnail = createThumbnail(file);
        Path stagedAvatar = null;
        try {
            Files.createDirectories(avatarRoot);
            stagedAvatar = Files.createTempFile(avatarRoot, "avatar-" + userId + "-", ".png");
            Files.write(stagedAvatar, thumbnail);
            // 同目录原子替换：失败保留旧头像，并发保存以最后成功替换者为准，不产生半张图片。
            Files.move(stagedAvatar, avatarPath(userId), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("头像更新成功 userId={}", userId);
            return avatarView(thumbnail);
        } catch (IOException exception) {
            throw storageFailure("保存", userId, exception);
        } finally {
            if (stagedAvatar != null) {
                try {
                    Files.deleteIfExists(stagedAvatar);
                } catch (IOException exception) {
                    LOGGER.warn("头像暂存文件清理失败 userId={}，保留文件供后续检查", userId, exception);
                }
            }
        }
    }

    @Override
    public AccountAvatarView resetCurrentAvatar() {
        long userId = requireCurrentUserId();
        try {
            Files.deleteIfExists(avatarPath(userId));
            LOGGER.info("头像恢复默认成功 userId={}", userId);
            return new AccountAvatarView(null);
        } catch (IOException exception) {
            throw storageFailure("恢复默认", userId, exception);
        }
    }

    private long requireCurrentUserId() {
        Long userId = CurrentOperator.id();
        if (userId == null || userId <= 0) throw new BusinessException("UNAUTHORIZED", "请先登录");
        return userId;
    }

    private Path avatarPath(long userId) {
        // 路径仅使用认证上下文的数字 ID，不接受客户端账号或文件名。
        return avatarRoot.resolve("user-" + userId + ".png");
    }

    private byte[] createThumbnail(MultipartFile file) {
        if (file == null || file.isEmpty()) throw invalidImage();
        if (file.getSize() > MAX_UPLOAD_BYTES) throw new BusinessException("AVATAR_TOO_LARGE", "头像文件不能超过 2 MB");
        try (InputStream upload = file.getInputStream()) {
            byte[] content = upload.readNBytes(MAX_UPLOAD_BYTES + 1);
            if (content.length > MAX_UPLOAD_BYTES) throw new BusinessException("AVATAR_TOO_LARGE", "头像文件不能超过 2 MB");
            try (var imageInput = new MemoryCacheImageInputStream(new ByteArrayInputStream(content))) {
                var readers = ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) throw invalidImage();
                var reader = readers.next();
                try {
                    if (!Set.of("png", "jpeg", "jpg", "webp").contains(reader.getFormatName().toLowerCase(Locale.ROOT))) throw invalidImage();
                    reader.setInput(imageInput, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
                        throw new BusinessException("AVATAR_DIMENSIONS_INVALID", "图片宽高均不能超过 4096 像素");
                    }
                    BufferedImage original = reader.read(0);
                    if (original == null) throw invalidImage();
                    BufferedImage thumbnail = new BufferedImage(AVATAR_SIZE, AVATAR_SIZE, BufferedImage.TYPE_INT_ARGB);
                    var graphics = thumbnail.createGraphics();
                    try {
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        int side = Math.min(width, height), left = (width - side) / 2, top = (height - side) / 2;
                        graphics.drawImage(original, 0, 0, AVATAR_SIZE, AVATAR_SIZE, left, top, left + side, top + side, null);
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
            LOGGER.warn("头像图片解析失败 userId={}，拒绝本次上传", CurrentOperator.id(), exception);
            throw invalidImage();
        }
    }

    private AccountAvatarView avatarView(byte[] image) {
        return new AccountAvatarView("data:image/png;base64," + Base64.getEncoder().encodeToString(image));
    }

    private BusinessException invalidImage() {
        return new BusinessException("AVATAR_IMAGE_INVALID", "请选择有效的 JPG、PNG 或 WebP 图片");
    }

    private IllegalStateException storageFailure(String operation, long userId, IOException exception) {
        return new IllegalStateException("头像" + operation + "失败 userId=" + userId, exception);
    }
}
