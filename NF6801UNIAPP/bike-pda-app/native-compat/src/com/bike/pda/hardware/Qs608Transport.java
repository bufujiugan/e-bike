package com.bike.pda.hardware;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.qs.n601.utils.PrintUtils;
import java.io.IOException;
import java.util.ArrayDeque;

/** 沿用厂商串口、1000 字节分包和忙电平规则；失败后不重发部分写入的数据。 */
public final class Qs608Transport {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ArrayDeque<Command> QUEUE = new ArrayDeque<>();
    private static boolean scheduled;
    private static boolean failed;
    private static long blockedAt = -1;
    private static final class Command {
        final byte[] bytes;
        final Runnable completed;
        int position;
        Command(byte[] bytes, Runnable completed) { this.bytes = bytes.clone(); this.completed = completed; }
    }
    private Qs608Transport() {}

    public static void send(byte[] bytes) {
        try { enqueue(bytes, null); }
        catch (RuntimeException cause) { Qs608Hardware.onTransportFailure(cause); }
    }
    public static void enqueue(byte[] bytes, Runnable completed) {
        if (bytes == null || bytes.length == 0) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            byte[] copy = bytes.clone();
            MAIN.post(() -> {
                try { enqueue(copy, completed); }
                catch (RuntimeException cause) { Qs608Hardware.onTransportFailure(cause); }
            });
            return;
        }
        if (failed) throw new IllegalStateException("608 serial transport failed; restart required");
        QUEUE.add(new Command(bytes, completed));
        if (!scheduled) { scheduled = true; MAIN.post(PUMP); }
    }

    /** 接收端已经中断时停止待发内容；可能已部分发送，不尝试恢复或自动重放。 */
    public static void stop() {
        failed = true; scheduled = false; QUEUE.clear();
        MAIN.removeCallbacks(PUMP);
    }
    private static final Runnable PUMP = Qs608Transport::pump;

    private static void pump() {
        Command command = QUEUE.peek();
        if (command == null) { scheduled = false; blockedAt = -1; return; }
        try {
            if (PrintUtils.mICommService == null || PrintUtils.mICommService.getGpioPrinterState() == 1) {
                long now = SystemClock.elapsedRealtime();
                if (blockedAt < 0) blockedAt = now;
                if (now - blockedAt >= 10000) throw new IOException("608 service/busy wait timed out");
                MAIN.postDelayed(PUMP, 10);
                return;
            }
            blockedAt = -1;
            if (PrintUtils.mOutputStream == null) throw new IOException("608 output stream unavailable");
            int length = Math.min(1000, command.bytes.length - command.position);
            PrintUtils.mOutputStream.write(command.bytes, command.position, length);
            command.position += length;
            if (command.position == command.bytes.length) {
                QUEUE.remove();
                if (command.completed != null) command.completed.run();
            }
            MAIN.post(PUMP);
        } catch (Exception cause) {
            // 串口写入可能已部分生效，停止整条队列，不补发、不自动重打。
            failed = true; scheduled = false; QUEUE.clear();
            Qs608Hardware.onTransportFailure(cause);
        }
    }
}
