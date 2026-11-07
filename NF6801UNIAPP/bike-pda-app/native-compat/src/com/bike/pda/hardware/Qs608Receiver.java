package com.bike.pda.hardware;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import com.qs.n601.utils.PrintUtils;
import java.io.IOException;
import java.util.Arrays;

/** 替换厂商已有读取线程的入口；仍只读取原来的串口，不创建第二个读者。 */
public final class Qs608Receiver {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile long generation;
    private static final Qs608ReceiveBuffer BUFFER = new Qs608ReceiveBuffer(new Qs608ReceiveBuffer.Listener() {
        public void barcode(String value) {
            Message message = new Message(); message.what = 0x123; message.obj = value;
            Qs608Hardware.onDecodedScan(message);
        }
        public void status(int value) {
            Qs608Hardware.onReceiveStatus(value);
        }
        public void rejected(String reason) { Qs608Hardware.onRejectedFrame(reason); }
    });
    private static final Runnable IDLE = BUFFER::idle;
    private Qs608Receiver() {}

    public static void beginScan() {
        generation++;
        MAIN.removeCallbacks(IDLE);
        BUFFER.reset();
    }

    public static void readLoop() {
        byte[] bytes = new byte[2048];
        try {
            if (PrintUtils.mInputStream == null) throw new IOException("608 input stream unavailable");
            while (!Thread.currentThread().isInterrupted()) {
                int count = PrintUtils.mInputStream.read(bytes);
                if (count < 0) throw new IOException("608 input stream closed");
                if (count == 0) { Thread.sleep(5); continue; }
                final byte[] chunk = Arrays.copyOf(bytes, count);
                final long receivedGeneration = generation;
                MAIN.post(() -> {
                    if (receivedGeneration != generation) return;
                    BUFFER.accept(chunk);
                    MAIN.removeCallbacks(IDLE);
                    MAIN.postDelayed(IDLE, 200);
                });
            }
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
            MAIN.post(() -> fail(cause));
        } catch (Exception cause) {
            MAIN.post(() -> fail(cause));
        }
    }

    private static void fail(Exception cause) {
        beginScan();
        Qs608Hardware.onReceiveFailure(cause);
    }
}
