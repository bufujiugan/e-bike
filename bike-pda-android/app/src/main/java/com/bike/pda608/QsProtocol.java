package com.bike.pda608;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;

/** 来源：608 硬解码 Demo PrintUtils；修正固定数组溢出及文本误过滤。 */
public final class QsProtocol {
    private QsProtocol() {}
    public static final byte[] SCAN = {0x1b, 0x23, 0x23, 0x35, 0x35, 0x44, 0x4e};
    public static final byte[] POWER_SCAN = {0x1b, 0x23, 0x23, 0x35, 0x36, 0x55, 0x50};
    public static final byte[] PRINT_DONE = {0x7a, (byte) 0xf8};
    public static final byte[] NO_PAPER = {0x14, 0, 0x0c, 0x0f};
    // 厂商示例把 ##55/##56 归为控制回执；UP与DN都不能进入业务扫码。
    private static final String[] SCAN_CONTROLS = {"##55UP", "##55DN", "##56UP", "##56DN"};

    public static boolean contains(byte[] bytes, byte[] part) {
        outer: for (int i = 0; i <= bytes.length - part.length; i++) {
            for (int j = 0; j < part.length; j++) if (bytes[i + j] != part[j]) continue outer;
            return true;
        }
        return false;
    }

    public static String scanText(byte[] bytes) {
        String text = normalizeScanPacket(bytes);
        if (incompleteControl(text) || text.startsWith("##55") || text.startsWith("##56")) return "";
        return text;
    }

    private static String normalizeScanPacket(byte[] bytes) {
        if (Arrays.equals(bytes, PRINT_DONE) || Arrays.equals(bytes, NO_PAPER)) return "";
        if (Arrays.equals(bytes, new byte[]{0}) || Arrays.equals(bytes, new byte[]{0x11})) return "";
        String text = new String(bytes, Charset.forName("GBK"));
        if (text.indexOf('\ufffd') >= 0) text = new String(bytes, Charset.forName("UTF-8"));
        text = text.trim();
        boolean changed;
        do {
            changed = false;
            for (String control : SCAN_CONTROLS) {
                if (text.startsWith(control)) { text = text.substring(control.length()).trim(); changed = true; }
                // 只剥离有帧分隔符的尾部回执，不误删条码中间或普通后缀中的相同文字。
                for (String separator : new String[]{"\r", "\n", "\u001b"}) {
                    String suffix = separator + control;
                    if (text.endsWith(suffix)) { text = text.substring(0, text.length() - suffix.length()).trim(); changed = true; }
                }
            }
        } while (changed);
        if (text.equals("start") || text.equals("55DN")) return "";
        return text;
    }

    private static boolean incompleteControl(String text) {
        int boundary = Math.max(Math.max(text.lastIndexOf('\r'), text.lastIndexOf('\n')), text.lastIndexOf('\u001b'));
        String tail = text.substring(boundary + 1);
        if (tail.isEmpty()) return false;
        for (String control : SCAN_CONTROLS) if (control.startsWith(tail) && !control.equals(tail)) return true;
        return false;
    }

    /** 回执被拆到多个串口包时保留半帧；纯回执不结束扫码等待。 */
    public static final class ScanDecoder {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        public synchronized String readPacket(byte[] bytes) {
            if (pending.size() + bytes.length > 8192) { reset(); return ""; }
            pending.write(bytes, 0, bytes.length);
            byte[] combined = pending.toByteArray();
            String text = normalizeScanPacket(combined);
            if (incompleteControl(text)) return "";
            reset();
            return scanText(combined);
        }
        public synchronized void reset() { pending.reset(); }
    }

    /** 24 点列式 ESC/P，保留二维码边缘，按动态缓冲区输出（含最后不足24行）。 */
    public static byte[] raster(int width, int height, int[] pixels, int leftPadding, boolean label) {
        if (width < 1 || leftPadding < 0 || width + leftPadding > 584 || height < 1 || height > 2400 || pixels.length != width * height)
            throw new IllegalArgumentException("标签尺寸超出打印范围");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, new byte[]{0x1b, 0x40, 0x1b, 0x61, 0, 0x1d, 0x23, 0x53, (byte)0xd1, 0x7a, (byte)0xf8, 0x4d, 0x1b, 0x33, 0});
        int totalWidth = width + leftPadding;
        for (int y = 0; y < height; y += 24) {
            write(out, new byte[]{0x1b, 0x2a, 33, (byte)totalWidth, (byte)(totalWidth >> 8)});
            for (int x = -leftPadding; x < width; x++) {
                for (int block = 0; block < 3; block++) {
                    int bits = 0;
                    for (int bit = 0; bit < 8; bit++) {
                        int row = y + block * 8 + bit;
                        if (x >= 0 && row < height) {
                            int color = pixels[row * width + x];
                            int gray = (299 * ((color >> 16) & 255) + 587 * ((color >> 8) & 255) + 114 * (color & 255)) / 1000;
                            if (((color >>> 24) & 255) > 127 && gray < 160) bits |= 1 << (7 - bit);
                        }
                    }
                    out.write(bits);
                }
            }
            out.write(10);
        }
        write(out, label ? new byte[]{0x1d, 0x0c} : new byte[]{10, 10, 10});
        write(out, new byte[]{0x1d, 0x23, 0x45});
        return out.toByteArray();
    }
    private static void write(ByteArrayOutputStream out, byte[] value) { out.write(value, 0, value.length); }
}
