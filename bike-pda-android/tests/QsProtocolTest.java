package com.bike.pda608;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;

public class QsProtocolTest {
    private static int checks;
    private static void check(boolean pass, String name) { checks++; if (!pass) throw new AssertionError(name); }
    private static byte[] packet(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    public static void main(String[] args) {
        check(QsProtocol.scanText("##55UP".getBytes(StandardCharsets.UTF_8)).isEmpty(), "608扫描控制回执不得作为业务编码");
        for (String control : new String[]{"##55UP", "##55DN", "##56UP", "##56DN"}) {
            check(QsProtocol.scanText(packet("\u001b" + control + "\r\n")).isEmpty(), "忽略带控制符的回执 " + control);
        }
        check(QsProtocol.scanText(packet("##55UP\r\nDJ2026110301\r\n##55DN")).equals("DJ2026110301"), "回执与真实码合包仍提取真实码");
        check(QsProtocol.scanText(packet("##55UPDJ2026110301")).equals("DJ2026110301"), "前置回执无换行合包");
        check(QsProtocol.scanText(packet("PART-##55UP-X")).equals("PART-##55UP-X"), "不模糊过滤普通编码");
        check(QsProtocol.scanText(packet("PART-##55UP")).equals("PART-##55UP"), "无帧分隔的普通后缀不能被删除");
        QsProtocol.ScanDecoder decoder = new QsProtocol.ScanDecoder();
        check(decoder.readPacket(packet("##55UP")).isEmpty(), "先收到回执仍等待");
        check(decoder.readPacket(packet("DJ2026110301\r\n")).equals("DJ2026110301"), "稍后真实二维码仍可接收");
        check(decoder.readPacket(packet("##5")).isEmpty(), "半个回执不泄露给业务");
        check(decoder.readPacket(packet("5U")).isEmpty(), "跨包回执继续等待");
        check(decoder.readPacket(packet("P\r\nDJ2026110301")).equals("DJ2026110301"), "分包回执拼合后保留扫码");
        check(decoder.readPacket(packet("DJ2026110301\r\n##55U")).isEmpty(), "尾部半帧回执暂存");
        check(decoder.readPacket(packet("P")).equals("DJ2026110301"), "尾部回执补全后发出原始扫码");
        decoder.readPacket(packet("##5")); decoder.reset();
        check(decoder.readPacket(packet("DJ2026110301")).equals("DJ2026110301"), "新扫描会话不继承旧半帧");
        check(QsProtocol.scanText("SN-MM-11\r\n".getBytes(StandardCharsets.UTF_8)).equals("SN-MM-11"), "合法编码不得因包含MM/11而丢失");
        check(QsProtocol.scanText("11".getBytes(StandardCharsets.UTF_8)).equals("11"), "ASCII 11 与设备0x11状态区别");
        check(QsProtocol.scanText(new byte[]{0x11}).isEmpty(), "丢弃状态字节");
        check(QsProtocol.scanText(QsProtocol.PRINT_DONE).isEmpty(), "打印完成字节不作为条码");
        check(QsProtocol.scanText(QsProtocol.NO_PAPER).isEmpty(), "缺纸字节不作为条码");
        check(QsProtocol.contains(new byte[]{0,0x7a,(byte)0xf8,0}, QsProtocol.PRINT_DONE), "识别完成帧");
        check(QsProtocol.contains(new byte[]{0x14,0,0x0c,0x0f}, QsProtocol.NO_PAPER), "识别缺纸帧");
        int[] pixels = new int[384 * 25]; Arrays.fill(pixels, 0xffffffff); pixels[0] = 0xff000000; pixels[24 * 384 + 383] = 0xff000000;
        byte[] raster = QsProtocol.raster(384,25,pixels,200,false);
        check(raster.length == 15 + 2 * (5 + 584 * 3 + 1) + 3 + 3, "不足24行尾块无溢出且无截断");
        check((raster[15 + 5 + 600] & 255) == 128, "左侧空白与首像素");
        int tail = 15 + (5 + 584 * 3 + 1) + 5 + 583 * 3;
        check((raster[tail] & 255) == 128, "末行末像素保留");
        byte[] label = QsProtocol.raster(1,1,new int[]{0xff000000},0,true);
        check(label[label.length-5] == 0x1d && label[label.length-4] == 0x0c, "标签走纸结束指令");
        boolean rejected = false; try { QsProtocol.raster(585,1,new int[585],0,false); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "拒绝超宽图片");
        System.out.println("PASS: " + checks + " protocol checks");
    }
}
