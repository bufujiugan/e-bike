package com.bike.pda.hardware;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;

/** 串口读取边界不是报文边界；先分离控制帧，再按行或空闲窗口解码条码。 */
public final class Qs608ReceiveBuffer {
    public interface Listener {
        void barcode(String value);
        void status(int value);
        void rejected(String reason);
    }
    public static final int CONTROL = 0, READY = 1, NO_PAPER = 2, PRINT_REPORT = 3;
    private static final int LIMIT = 8192;
    private static final byte[][] TOKENS = {
        ascii("\u001b##55UP"), ascii("\u001b##55DN"), ascii("\u001b##56UP"), ascii("\u001b##56DN"),
        ascii("##55UP"), ascii("##55DN"), ascii("##56UP"), ascii("##56DN"),
        {0x14, 0, 0x0C, 0x0F}, {0x14, 0, 0, 0x0F},
        // Demo 以 7A F8 识别打印回报；完整形式优先，避免尾字节变成假条码。
        {(byte) 0xD1, 0x7A, (byte) 0xF8, 0x4D}, {0x7A, (byte) 0xF8}
    };
    private final Listener listener;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private byte[] pending = new byte[0];
    private boolean damaged;

    public Qs608ReceiveBuffer(Listener listener) { this.listener = listener; }
    private static byte[] ascii(String text) { return text.getBytes(Charset.forName("US-ASCII")); }

    public void reset() { pending = new byte[0]; line.reset(); damaged = false; }

    public void accept(byte[] bytes) {
        // 单次读取有固定上限；总长度超限后丢弃至明确边界，不把尾段当成新条码。
        byte[] combined = Arrays.copyOf(pending, pending.length + bytes.length);
        System.arraycopy(bytes, 0, combined, pending.length, bytes.length);
        pending = combined;
        consume(false);
    }

    public void idle() { consume(true); finishLine(); }

    private void consume(boolean idle) {
        int position = 0;
        while (position < pending.length) {
            int token = -1;
            boolean partial = false;
            for (int index = 0; index < TOKENS.length; index++) {
                byte[] candidate = TOKENS[index];
                int available = Math.min(candidate.length, pending.length - position);
                boolean match = true;
                for (int offset = 0; offset < available; offset++)
                    if (pending[position + offset] != candidate[offset]) { match = false; break; }
                if (!match) continue;
                if (available == candidate.length) { token = index; break; }
                partial = true;
            }
            if (token >= 0) {
                position += TOKENS[token].length;
                // 无分隔符地嵌入条码内部时边界不明，拒绝整行而不是删除片段后制造新码。
                if (line.size() > 0) damaged = true;
                listener.status(token == 8 ? NO_PAPER : token == 9 ? READY : token >= 10 ? PRINT_REPORT : CONTROL);
                continue;
            }
            if (partial && !idle) break;
            // 单个 z/# 也可能是真实条码尾字节；只有明确的控制前缀才按残帧拒绝。
            if (partial && idle && pending[position] == '#' && pending.length-position >= 2
                    && pending[position+1] == '#') { damaged = true; position = pending.length; break; }
            int value = pending[position++] & 255;
            if (value == 13 || value == 10) { finishLine(); continue; }
            // 厂商单字节流控只在行外接收；码内异常字节导致整行拒绝，不能悄悄改码。
            if ((value == 0 || value == 0x11) && line.size() == 0 && !damaged) {
                listener.status(CONTROL); continue;
            }
            if (value < 32 || value == 127) { damaged = true; continue; }
            if (line.size() >= LIMIT) damaged = true;
            if (!damaged) line.write(value);
        }
        pending = Arrays.copyOfRange(pending, position, pending.length);
    }

    private void finishLine() {
        if (damaged) listener.rejected("invalid-or-oversized-frame");
        else if (line.size() > 0) {
            try {
                String value = decode(line.toByteArray()).trim();
                if (Qs608Protocol.isBarcode(value)) listener.barcode(value);
                else listener.rejected("reserved-or-invalid-payload");
            } catch (CharacterCodingException cause) { listener.rejected("invalid-encoding"); }
        }
        line.reset(); damaged = false;
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        try { return strictDecode(bytes, "UTF-8"); }
        catch (CharacterCodingException cause) { return strictDecode(bytes, "GBK"); }
    }

    private static String strictDecode(byte[] bytes, String charset) throws CharacterCodingException {
        return Charset.forName(charset).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
}
