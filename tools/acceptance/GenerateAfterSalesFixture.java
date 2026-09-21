import java.nio.file.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.jcodec.api.SequenceEncoder;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;

/** Generates small, real media for repeatable HTTP upload acceptance; does not run the server. */
class GenerateAfterSalesFixture {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]); Files.createDirectories(directory);
        ImageIO.write(new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB), "png", directory.resolve("fault.png").toFile());
        var encoder = SequenceEncoder.createSequenceEncoder(directory.resolve("fault.mp4").toFile(), 24);
        for (int index = 0; index < 3; index++) {
            var image = Picture.create(32, 32, ColorSpace.RGB);
            java.util.Arrays.fill(image.getPlaneData(0), (byte) (index * 20));
            encoder.encodeNativeFrame(image);
        }
        encoder.finish();
    }
}
