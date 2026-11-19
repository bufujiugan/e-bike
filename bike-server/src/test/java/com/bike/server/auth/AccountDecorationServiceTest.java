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

class AccountDecorationServiceTest {
    @TempDir Path directory;

    @Test
    void savesCanonicalImageAndPersistsAcrossServiceInstances() throws Exception {
        try (var operator = mockStatic(CurrentOperator.class)) {
            operator.when(CurrentOperator::id).thenReturn(41L);
            var service = new AccountDecorationServiceImpl(directory.toString());
            assertThat(service.getCurrentDecoration().imageDataUrl()).isNull();
            var saved = service.updateCurrentDecoration(image(400, 240));
            byte[] bytes = Base64.getDecoder().decode(saved.imageDataUrl().split(",")[1]);
            var decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            assertThat(decoded.getWidth()).isEqualTo(384);
            assertThat(decoded.getHeight()).isEqualTo(230);
            assertThat(decoded.getRGB(192, 115)).isEqualTo(Color.BLUE.getRGB());
            assertThat(new AccountDecorationServiceImpl(directory.toString()).getCurrentDecoration()).isEqualTo(saved);
            assertThat(directory.resolve("decorations/user-41.png")).exists();
        }
    }

    @Test
    void preservesTransparencyAndDoesNotEnlargeSmallPictures() throws Exception {
        try (var operator = mockStatic(CurrentOperator.class)) {
            operator.when(CurrentOperator::id).thenReturn(41L);
            var original = new BufferedImage(80, 120, BufferedImage.TYPE_INT_ARGB);
            original.setRGB(0, 60, Color.RED.getRGB());
            original.setRGB(79, 60, Color.BLUE.getRGB());
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(original, "png", bytes);
            var service = new AccountDecorationServiceImpl(directory.toString());
            var saved = service.updateCurrentDecoration(new MockMultipartFile("file", "pet.png", "image/png", bytes.toByteArray()));
            var decoded = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(saved.imageDataUrl().split(",")[1])));
            assertThat(decoded.getWidth()).isEqualTo(80);
            assertThat(decoded.getHeight()).isEqualTo(120);
            assertThat(decoded.getRGB(40, 0) >>> 24).isZero();
            assertThat(decoded.getRGB(0, 60)).isEqualTo(Color.RED.getRGB());
            assertThat(decoded.getRGB(79, 60)).isEqualTo(Color.BLUE.getRGB());
        }
    }

    @Test
    void isolatesAccountsAndResetIsIdempotent() throws Exception {
        try (var operator = mockStatic(CurrentOperator.class)) {
            var service = new AccountDecorationServiceImpl(directory.toString());
            operator.when(CurrentOperator::id).thenReturn(41L);
            var saved = service.updateCurrentDecoration(image(20, 40));
            operator.when(CurrentOperator::id).thenReturn(42L);
            assertThat(service.getCurrentDecoration().imageDataUrl()).isNull();
            service.resetCurrentDecoration();
            operator.when(CurrentOperator::id).thenReturn(41L);
            assertThat(service.getCurrentDecoration()).isEqualTo(saved);
            assertThat(service.resetCurrentDecoration().imageDataUrl()).isNull();
            assertThat(service.resetCurrentDecoration().imageDataUrl()).isNull();
            assertThat(service.getCurrentDecoration().imageDataUrl()).isNull();
        }
    }

    @Test
    void rejectsInvalidUploadsWithoutReplacingExistingDecoration() throws Exception {
        try (var operator = mockStatic(CurrentOperator.class)) {
            operator.when(CurrentOperator::id).thenReturn(41L);
            var service = new AccountDecorationServiceImpl(directory.toString());
            var saved = service.updateCurrentDecoration(image(20, 20));
            assertThatThrownBy(() -> service.updateCurrentDecoration(new MockMultipartFile("file", "fake.png", "image/png", "<script>bad</script>".getBytes())))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("有效");
            assertThatThrownBy(() -> service.updateCurrentDecoration(new MockMultipartFile("file", new byte[2 * 1024 * 1024 + 1])))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("2 MB");
            assertThatThrownBy(() -> service.updateCurrentDecoration(image(4097, 1)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("4096");
            assertThatThrownBy(() -> service.updateCurrentDecoration(new MockMultipartFile("file", new byte[0])))
                    .isInstanceOf(BusinessException.class);
            assertThat(service.getCurrentDecoration()).isEqualTo(saved);
        }
    }

    @Test
    void requiresAuthenticatedUserForAllOperations() {
        try (var operator = mockStatic(CurrentOperator.class)) {
            operator.when(CurrentOperator::id).thenReturn(null);
            var service = new AccountDecorationServiceImpl(directory.toString());
            assertThatThrownBy(service::getCurrentDecoration).isInstanceOf(BusinessException.class).hasMessage("请先登录");
            assertThatThrownBy(() -> service.updateCurrentDecoration(null)).isInstanceOf(BusinessException.class).hasMessage("请先登录");
            assertThatThrownBy(service::resetCurrentDecoration).isInstanceOf(BusinessException.class).hasMessage("请先登录");
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
