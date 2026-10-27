package com.bike.server.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

import com.bike.server.common.BusinessException;
import com.bike.server.common.CurrentOperator;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class AccountAvatarServiceTest {
    @TempDir Path directory;

    @Test
    void savesCanonicalImageAndPersistsAcrossServiceInstances() throws Exception {
        try (var operator = mockStatic(CurrentOperator.class)) {
            operator.when(CurrentOperator::id).thenReturn(41L);
            var service = new AccountAvatarServiceImpl(directory.toString());
            assertThat(service.getCurrentAvatar().imageDataUrl()).isNull();
            var saved = service.updateCurrentAvatar(image(400, 240));
            byte[] bytes = Base64.getDecoder().decode(saved.imageDataUrl().split(",")[1]);
            var decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            assertThat(decoded.getWidth()).isEqualTo(256);
            assertThat(decoded.getHeight()).isEqualTo(256);
            assertThat(new AccountAvatarServiceImpl(directory.toString()).getCurrentAvatar()).isEqualTo(saved);
            assertThat(directory.resolve("avatars/user-41.png")).exists();
        }
    }

    @Test
    void isolatesAccountsAndResetIsIdempotent() throws Exception {
        try (var operator = mockStatic(CurrentOperator.class)) {
            var service = new AccountAvatarServiceImpl(directory.toString());
            operator.when(CurrentOperator::id).thenReturn(41L);
            var saved = service.updateCurrentAvatar(image(20, 40));
            operator.when(CurrentOperator::id).thenReturn(42L);
            assertThat(service.getCurrentAvatar().imageDataUrl()).isNull();
            service.resetCurrentAvatar();
            operator.when(CurrentOperator::id).thenReturn(41L);
            assertThat(service.getCurrentAvatar()).isEqualTo(saved);
            assertThat(service.resetCurrentAvatar().imageDataUrl()).isNull();
            assertThat(service.resetCurrentAvatar().imageDataUrl()).isNull();
            assertThat(service.getCurrentAvatar().imageDataUrl()).isNull();
        }
    }

    @Test
    void rejectsInvalidUploadsWithoutReplacingExistingAvatar() throws Exception {
        try (var operator = mockStatic(CurrentOperator.class)) {
            operator.when(CurrentOperator::id).thenReturn(41L);
            var service = new AccountAvatarServiceImpl(directory.toString());
            var saved = service.updateCurrentAvatar(image(20, 20));
            assertThatThrownBy(() -> service.updateCurrentAvatar(new MockMultipartFile("file", "fake.png", "image/png", "<script>bad</script>".getBytes())))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("有效");
            assertThatThrownBy(() -> service.updateCurrentAvatar(new MockMultipartFile("file", new byte[2 * 1024 * 1024 + 1])))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("2 MB");
            assertThatThrownBy(() -> service.updateCurrentAvatar(image(4097, 1)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("4096");
            assertThatThrownBy(() -> service.updateCurrentAvatar(new MockMultipartFile("file", new byte[0])))
                    .isInstanceOf(BusinessException.class);
            assertThat(service.getCurrentAvatar()).isEqualTo(saved);
        }
    }

    @Test
    void requiresAuthenticatedUserForAllOperations() {
        try (var operator = mockStatic(CurrentOperator.class)) {
            operator.when(CurrentOperator::id).thenReturn(null);
            var service = new AccountAvatarServiceImpl(directory.toString());
            assertThatThrownBy(service::getCurrentAvatar).isInstanceOf(BusinessException.class).hasMessage("请先登录");
            assertThatThrownBy(() -> service.updateCurrentAvatar(null)).isInstanceOf(BusinessException.class).hasMessage("请先登录");
            assertThatThrownBy(service::resetCurrentAvatar).isInstanceOf(BusinessException.class).hasMessage("请先登录");
        }
    }

    private MockMultipartFile image(int width, int height) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return new MockMultipartFile("file", "../../outside.png", "image/png", bytes.toByteArray());
    }
}
