import com.bike.pda.hardware.Qs608Protocol;
import java.util.Arrays;

public final class Qs608ProtocolTest {
    public static void main(String[] args) {
        for (String value : new String[]{"##55UP", "\u001b##55UP\r\n", "##55DN", "##56UP",
                "55DN", "##55UP##55DN", "BIKE-01##55UP", "start", "BE", "P", "11", "MM",
                "no paper", "find bl end err", "t_pwr 0", "err 1", "BIKE\u0000SN", "\ufffdz\ufffdM", "褄鳰", ""})
            check(!Qs608Protocol.isBarcode(value), "Control frame accepted: " + value);
        check(!Qs608Protocol.isBarcode(null), "Null accepted");
        for (String value : new String[]{"BIKE-PDA-SELF-TEST", "BIKE-20261102-001",
                "4006381333931", "036000291452", "车辆-001", "https://bike.example/vehicle/55UP",
                "FRAME-START-11", "START123", "\r\nBIKE-01\r\n"})
            check(Qs608Protocol.isBarcode(value), "Barcode rejected: " + value);
        byte[] raster = {0x1D, 0x76, 0x30, 0x00, (byte) 0xFF};
        byte[] saved = raster.clone();
        byte[] expected = {0x1D, 0x23, 0x53, (byte) 0xD1, 0x7A, (byte) 0xF8, 0x4D,
                0x1D, 0x76, 0x30, 0x00, (byte) 0xFF, 0x1D, 0x0C, 0x1D, 0x23, 0x45};
        check(Arrays.equals(expected, Qs608Protocol.framePrint(raster, true, true)), "Vendor label frame mismatch");
        check(Arrays.equals(raster, saved), "Raster mutated");
        check(Qs608Protocol.framePrint(raster, false, true).length == raster.length + 14, "Continuous feed mismatch");
        check(Qs608Protocol.framePrint(raster, false, false).length == raster.length + 10, "No-feed mismatch");
        check(Qs608Protocol.isPrintStart(expected), "Start marker missing");
        for (int height = 1; height <= 799; height++) {
            final int lastRow = height - 1;
            byte[] image = Qs608Protocol.rasterize(570, height, (x, y) -> y == lastRow ? 0xFF000000 : 0xFFFFFFFF);
            int bands = (height + 23) / 24;
            check(image.length == 3 + bands * 1716, "Wrong exact raster length");
            int lastBand = 3 + (bands - 1) * 1716;
            int part = (lastRow % 24) / 8;
            int mask = 1 << (7 - lastRow % 8);
            check((image[lastBand + 5 + 569 * 3 + part] & 255) == mask, "Last pixel truncated at height " + height);
            check(image[image.length - 1] == 10, "Trailing garbage or missing feed");
        }
        System.out.println("PASS: control-frame rejection, real barcodes, exact vendor print envelope");
        System.out.println("PASS: 799 label heights, exact raster size and final black pixel preserved");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
