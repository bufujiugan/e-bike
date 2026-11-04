package com.bike.pda608;

import android.content.*;
import android.os.*;
import android.util.Log;
import android_serialport_api.MyApp;
import android_serialport_api.SerialPort;
import com.sdk.ICommService;
import java.io.*;
import java.util.concurrent.*;

/** 同一前台会话只拥有一个串口；读写分离，所有设备写操作串行执行。 */
final class Qs608Hardware implements AutoCloseable {
    interface Listener { void scan(String code); void status(String message); void printed(String id, String state, String message); }
    private final Context context;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private volatile boolean closed, ready;
    private boolean bound;
    private volatile long scanUntil;
    private volatile String printId;
    private volatile CountDownLatch printLatch;
    private volatile boolean paperError;
    private volatile ICommService gpio;
    private MyApp sdk;
    private SerialPort port;
    private InputStream input;
    private OutputStream output;
    private Thread reader;
    private final QsProtocol.ScanDecoder scanDecoder = new QsProtocol.ScanDecoder();
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (closed) return;
            gpio = ICommService.Stub.asInterface(binder);
            writer.execute(() -> {
                try {
                    gpio.CtrlPrinterOn(false);
                    for (int pin : new int[]{72, 150, 151, 158}) gpio.setGpioOut(pin, 1);
                    Thread.sleep(500);
                    if (closed) return;
                    synchronized (Qs608Hardware.this) {
                        if (closed) return;
                        sdk = new MyApp(); port = sdk.getSerialPort();
                        input = port.getInputStream(); output = port.getOutputStream();
                        reader = new Thread(Qs608Hardware.this::readLoop, "qs608-reader"); reader.start();
                    }
                    Thread.sleep(1500);
                    // 官方 608/6801 软解码与硬解码 Demo 都在初始化时拉高扫描头电源（##56UP），
                    // 软解码模式曾跳过此步，导致扫描头不上电、点按钮无光无声。
                    send(QsProtocol.POWER_SCAN);
                    send(new byte[]{0x1b,0x23,0x23,0x46,0x54,0x4b,0x54,0x31});
                    send(new byte[]{0x1b,0x23,0x23,0x53,0x54,0x44,0x50,0x27});
                    ready = true; status("608 扫码与打印接口已就绪");
                } catch (Exception | LinkageError error) { ready = false; Log.e("BikePda608", "Hardware initialization failed", error); status("608 串口不可用，请检查设备型号及是否有测试程序占用"); }
            });
        }
        public void onServiceDisconnected(ComponentName name) { ready = false; gpio = null; status("608 系统设备服务已断开"); }
    };

    Qs608Hardware(Context context, Listener listener) { this.context = context; this.listener = listener; }
    void open() {
        try {
            bound = context.bindService(new Intent("comm_com_service").setPackage("com.android.settings"), connection, Context.BIND_AUTO_CREATE);
            if (!bound) status("未找到608系统设备服务；软解码设备可在设置中切换广播模式");
        } catch (RuntimeException error) { Log.e("BikePda608", "Device service unavailable", error); status("608 系统设备服务不可访问"); }
    }
    boolean isReady() { return ready && !closed; }
    boolean isPrinting() { return printId != null; }
    void scan() {
        if (!isReady()) { status("设备初始化中或串口不可用，请查看设备设置"); return; }
        if (isPrinting()) { status("正在打印，请完成后再扫描"); return; }
        // 上一轮等待窗口未结束时不再静默忽略：现场连续点击按钮必须能再次点亮扫描头。
        scanDecoder.reset();
        scanUntil = SystemClock.elapsedRealtime() + 10000;
        writer.execute(() -> {
            try {
                gpio.setGpioOut(167, 1);
                Thread.sleep(100);
                send(QsProtocol.SCAN);
                Log.d("BikePda608", "Scan command sent to serial port");
            } catch (Exception error) { scanUntil = 0; Log.e("BikePda608", "Scan trigger failed", error); status("扫描启动失败，请检查设备连接"); }
        });
    }

    synchronized void print(String id, byte[] bytes, boolean label) {
        if (!isReady() || printId != null || SystemClock.elapsedRealtime() < scanUntil) {
            listener.printed(id, "FAILED", "设备未就绪、正在扫码或打印，请核对后回报失败"); return;
        }
        // 先持久化再送印：应用重启后同一任务不能再次发送。
        if (context.getSharedPreferences("printed-jobs", 0).contains(id)) {
            listener.printed(id, "UNKNOWN", "此任务已有送印记录，请核对实物并回报；补打须创建新任务"); return;
        }
        if (!context.getSharedPreferences("printed-jobs", 0).edit().putLong(id, System.currentTimeMillis()).commit()) {
            listener.printed(id, "FAILED", "无法保存送印记录，已阻止打印"); return;
        }
        printId = id; paperError = false; printLatch = new CountDownLatch(1);
        writer.execute(() -> {
            String state = "UNKNOWN", message = "打印结果未知，请核对实物并回报，勿重复送印";
            try {
                send(new byte[]{0x1f,0x1b,0x1f,(byte)0x80,4,5,6,(byte)(label ? 0x44 : 0x66)});
                send(new byte[]{0x1d,0x28,0x46,4,0,1,0,52,0});
                send(bytes);
                if (printLatch.await(25, TimeUnit.SECONDS) && !closed) {
                    if (paperError) { state = "FAILED"; message = "打印机报告缺纸，请检查是否已部分出纸，核对后回报"; }
                    else { state = "SUBMITTED"; message = "608 返回完成信号，请核对标签清晰度及二维码，再回报打印结果"; }
                } else { ready = false; status("打印结果未知，请先核对实物，重新进入应用后再作业"); }
            } catch (Exception error) { ready = false; Log.e("BikePda608", "Print interrupted; no automatic retry", error); }
            finally {
                printId = null;
                final String result = state, detail = message;
                ui.post(() -> listener.printed(id, result, detail));
            }
        });
    }

    private void send(byte[] bytes) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 30000;
        for (int offset = 0; offset < bytes.length;) {
            if (closed || gpio == null) throw new IOException("device closed");
            if (SystemClock.elapsedRealtime() > deadline) throw new IOException("printer busy timeout");
            if (gpio.getGpioPrinterState() == 1) { Thread.sleep(5); continue; }
            int size = Math.min(4096, bytes.length - offset);
            output.write(bytes, offset, size); offset += size;
        }
        output.flush();
    }

    private void readLoop() {
        ByteArrayOutputStream packet = new ByteArrayOutputStream(); long last = 0;
        try {
            while (!closed) {
                int count = input.available();
                if (count > 0) {
                    byte[] chunk = new byte[Math.min(count, 4096)]; int read = input.read(chunk);
                    if (read > 0) { packet.write(chunk, 0, read); last = SystemClock.elapsedRealtime(); }
                    if (packet.size() > 8192) { packet.reset(); scanUntil = 0; status("设备返回数据过长，已忽略，请重新扫描"); }
                } else {
                    if (packet.size() > 0 && SystemClock.elapsedRealtime() - last >= 200) { receive(packet.toByteArray()); packet.reset(); }
                    Thread.sleep(10);
                }
            }
        } catch (Exception error) { if (!closed) { ready = false; Log.e("BikePda608", "Device read failed", error); status("设备读取中断，请退出应用后重新进入"); } }
    }
    private void receive(byte[] data) {
        if (printId != null) {
            if (QsProtocol.contains(data, QsProtocol.NO_PAPER)) { paperError = true; printLatch.countDown(); }
            else if (QsProtocol.contains(data, QsProtocol.PRINT_DONE)) printLatch.countDown();
        } else if (SystemClock.elapsedRealtime() < scanUntil) {
            String code = scanDecoder.readPacket(data);
            if (!code.isEmpty()) {
                scanUntil = 0;
                Log.d("BikePda608", "Barcode received, length=" + code.length());
                ui.post(() -> { if (!closed) listener.scan(code); });
            } else Log.d("BikePda608", "Scanner control/status or partial frame ignored; still waiting for barcode");
        }
    }
    private void status(String message) { ui.post(() -> { if (!closed) listener.status(message); }); }
    @Override public synchronized void close() {
        closed = true; ready = false; scanUntil = 0;
        scanDecoder.reset();
        if (printLatch != null) printLatch.countDown();
        writer.shutdownNow(); if (reader != null) reader.interrupt();
        if (sdk != null) sdk.closeSerialPort();
        if (bound) { context.unbindService(connection); bound = false; }
    }
}
