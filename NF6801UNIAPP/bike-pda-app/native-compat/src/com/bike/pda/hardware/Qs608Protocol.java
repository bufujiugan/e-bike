package com.bike.pda.hardware;

import java.util.regex.Pattern;

/** 608 Demo 的控制帧边界；不依赖 Android，便于直接回归验证。 */
public final class Qs608Protocol {
    public interface PixelReader { int pixel(int x, int y); }
    private static final Pattern STATUS = Pattern.compile(
            "^(?:start|BE|P|11|MM|no paper|find bl end err|t_pwr(?: .*)?|err(?: .*)?)$",
            Pattern.CASE_INSENSITIVE);
    private static final byte[] PRINT_START = {0x1D, 0x23, 0x53, (byte) 0xD1, 0x7A, (byte) 0xF8, 0x4D};
    private static final byte[] PRINT_END = {0x1D, 0x23, 0x45};

    private Qs608Protocol() {}

    /** 保持厂商 ESC * 24 点列格式，精确计算空间，不截断尾行或发送多余零字节。 */
    public static byte[] rasterize(int width, int height, PixelReader pixels) {
        if (width < 1 || width > 570 || height < 1 || height > 8000)
            throw new IllegalArgumentException("Invalid 608 raster dimensions");
        int bands = (height + 23) / 24;
        byte[] bytes = new byte[3 + bands * (5 + width * 3 + 1)];
        int position = 0;
        bytes[position++] = 0x1B; bytes[position++] = 0x33; bytes[position++] = 0;
        for (int band = 0; band < bands; band++) {
            bytes[position++] = 0x1B; bytes[position++] = 0x2A; bytes[position++] = 33;
            bytes[position++] = (byte) (width % 256); bytes[position++] = (byte) (width / 256);
            for (int x = 0; x < width; x++) {
                for (int part = 0; part < 3; part++) {
                    int column = 0;
                    for (int bit = 0; bit < 8; bit++) {
                        int y = band * 24 + part * 8 + bit;
                        int pixel = y < height ? pixels.pixel(x, y) : 0xFFFFFFFF;
                        int red = (pixel >> 16) & 255, green = (pixel >> 8) & 255, blue = pixel & 255;
                        int gray = (int) (0.299 * red + 0.587 * green + 0.114 * blue);
                        column = (column << 1) | (gray < 200 ? 1 : 0);
                    }
                    bytes[position++] = (byte) column;
                }
            }
            bytes[position++] = 0x0A;
        }
        return bytes;
    }

    public static boolean isBarcode(Object value) {
        if (!(value instanceof String)) return false;
        String text = ((String) value).trim();
        if (text.isEmpty() || text.contains("##55") || text.contains("##56") || text.equals("55DN")
                || text.indexOf(0xFFFD) >= 0 || text.contains("褄") || text.contains("鳰"))
            return false;
        for (int i = 0; i < text.length(); i++)
            if (text.charAt(i) < 32 || text.charAt(i) == 127) return false;
        return !STATUS.matcher(text).matches();
    }

    /** 与硬解码 Demo printBitmap 一致：起始 + 点阵 + 可选走纸 + 结束。 */
    public static byte[] framePrint(byte[] raster, boolean label, boolean tearPaper) {
        if (raster == null || raster.length == 0) throw new IllegalArgumentException("Empty print raster");
        byte[] feed = label ? new byte[]{0x1D, 0x0C}
                : tearPaper ? new byte[]{0x20, 0x0A, 0x0A, 0x0A} : new byte[0];
        byte[] frame = new byte[PRINT_START.length + raster.length + feed.length + PRINT_END.length];
        int offset = 0;
        for (byte[] part : new byte[][]{PRINT_START, raster, feed, PRINT_END}) {
            System.arraycopy(part, 0, frame, offset, part.length);
            offset += part.length;
        }
        return frame;
    }

    public static boolean isPrintStart(byte[] bytes) {
        if (bytes == null || bytes.length < PRINT_START.length) return false;
        for (int i = 0; i < PRINT_START.length; i++)
            if (bytes[i] != PRINT_START[i]) return false;
        return true;
    }
}
