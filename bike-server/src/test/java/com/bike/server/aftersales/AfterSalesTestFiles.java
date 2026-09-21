package com.bike.server.aftersales;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.jcodec.api.SequenceEncoder;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;

final class AfterSalesTestFiles {
    private AfterSalesTestFiles() {}
    static byte[] image(String format) throws Exception {
        try (var bytes = new ByteArrayOutputStream()) {
            var picture = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
            picture.setRGB(10, 10, 0xff224488);
            ImageIO.write(picture, format, bytes);
            return bytes.toByteArray();
        }
    }
    static byte[] pdf() throws Exception {
        try (var document = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            document.addPage(new PDPage()); document.save(bytes); return bytes.toByteArray();
        }
    }
    static byte[] video(Path directory) throws Exception {
        Path file = Files.createTempFile(directory, "video-fixture-", ".mp4");
        try {
            var encoder = SequenceEncoder.createSequenceEncoder(file.toFile(), 24);
            for (int i = 0; i < 3; i++) {
                var picture = Picture.create(32, 32, ColorSpace.RGB);
                java.util.Arrays.fill(picture.getPlaneData(0), (byte) (i * 20));
                encoder.encodeNativeFrame(picture);
            }
            encoder.finish();
            return Files.readAllBytes(file);
        } finally { Files.deleteIfExists(file); }
    }
}
