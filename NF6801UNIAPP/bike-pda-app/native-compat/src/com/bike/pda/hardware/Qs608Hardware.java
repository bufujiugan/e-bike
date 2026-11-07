package com.bike.pda.hardware;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.os.Message;
import android.graphics.Bitmap;
import android.media.ToneGenerator;
import android.media.AudioManager;
import android.util.Log;
import com.qs.n601.utils.PrintUtils;
import org.json.JSONObject;

/** 复用厂商串口单例，补齐 608 Demo 的电源控制，不另开串口。 */
public final class Qs608Hardware {
    private static final String TAG = "Bike608";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean initialized;
    private static boolean powered;
    private static boolean ready;
    private static boolean receiveFailed;
    private static boolean blackMark;
    private static int offset;
    private static long startedAt;
    private static long poweredAt;
    private static long lastScanAt;
    private static String error = "设备尚未初始化";
    private static Context application;
    private static String scanState = "IDLE";
    private static String scanMessage = "";
    private static String printState = "IDLE";
    private static String printMessage = "";
    private static int printSequence;
    private static int ignoredFrames;
    private static ToneGenerator tone;

    private Qs608Hardware() {}

    public static void open(Context context, boolean useBlackMark, int startOffset) {
        boolean changed = blackMark != useBlackMark || offset != startOffset;
        if (initialized && changed && (printState.equals("PREPARING") || printState.equals("SENDING")))
            throw new IllegalStateException("打印发送中不能修改纸张参数");
        blackMark = useBlackMark;
        offset = startOffset;
        if (initialized) {
            if (ready && changed) configureLabel();
            return;
        }
        initialized = true;
        startedAt = SystemClock.elapsedRealtime();
        error = "正在连接 608 电源服务与串口";
        application = context.getApplicationContext();
        try {
            PrintUtils.initPrintUtils(application);
            // 与硬解码 Demo 相同的物理键广播，只注册一次。
            application.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    requestScan();
                }
            }, new IntentFilter("qsapi.intent.key_scan_down"));
            MAIN.post(Qs608Hardware::finishInitialization);
        } catch (Exception cause) {
            error = "608 初始化失败，请完全退出厂商 Demo 后重新启动本应用";
            Log.e(TAG, "608 initialization failed", cause);
        }
    }

    private static void finishInitialization() {
        if (receiveFailed) return;
        try {
            if (!powered && PrintUtils.mICommService != null) {
                // 608 Demo AIDL 第三个方法为 CtrlPrinterOn(boolean)。旧插件接口只有前两个方法。
                Parcel request = Parcel.obtain();
                Parcel response = Parcel.obtain();
                try {
                    request.writeInterfaceToken("com.sdk.ICommService");
                    request.writeInt(0); // Demo: CtrlPrinterOn(false)，然后置高 GPIO。
                    if (!PrintUtils.mICommService.asBinder().transact(3, request, response, 0))
                        throw new IllegalStateException("608 CtrlPrinterOn transaction unsupported");
                    response.readException();
                    boolean vendorResult = response.readInt() != 0;
                    Log.i(TAG, "CtrlPrinterOn(false) returned " + vendorResult);
                } finally {
                    response.recycle();
                    request.recycle();
                }
                for (int pin : new int[]{72, 150, 151, 158})
                    PrintUtils.mICommService.setGpioOut(pin, 1);
                powered = true;
                poweredAt = SystemClock.elapsedRealtime();
            }
            long now = SystemClock.elapsedRealtime();
            if (powered && now - startedAt >= 2700 && now - poweredAt >= 500) {
                if (PrintUtils.mInputStream == null || PrintUtils.mOutputStream == null)
                    throw new IllegalStateException("608 serial stream unavailable");
                PrintUtils.open();
                configureLabel();
                ready = true;
                error = "";
                Log.i(TAG, "608 service, serial and label initialization completed");
                return;
            }
            if (now - startedAt >= 10000) {
                error = "608 电源服务连接超时，请完全退出厂商 Demo 并重启本应用";
                Log.e(TAG, "608 service initialization timed out");
                return;
            }
            MAIN.postDelayed(Qs608Hardware::finishInitialization, 100);
        } catch (Exception cause) {
            ready = false;
            error = "608 上电或串口初始化失败，请检查设备型号及系统服务";
            Log.e(TAG, "608 power initialization failed", cause);
        }
    }

    private static void configureLabel() {
        PrintUtils.labelEnable(blackMark);
        PrintUtils.setStarNum(offset);
    }

    public static JSONObject status() {
        JSONObject result = new JSONObject();
        try {
            boolean connected = PrintUtils.mICommService != null
                    && PrintUtils.mICommService.asBinder().pingBinder();
            result.put("ready", ready && connected);
            result.put("initializing", initialized && !ready && SystemClock.elapsedRealtime() - startedAt < 10000
                    && error.equals("正在连接 608 电源服务与串口"));
            result.put("message", ready && !connected ? "608 电源服务已断开，请重启应用" : error);
            result.put("compatVersion", "608-3");
            result.put("scanState", scanState);
            result.put("scanMessage", scanMessage);
            result.put("ignoredFrames", ignoredFrames);
            result.put("printState", printState);
            result.put("printMessage", printMessage);
            result.put("printSequence", printSequence);
        } catch (Exception cause) {
            Log.e(TAG, "608 status serialization failed", cause);
            throw new IllegalStateException("608 status unavailable", cause);
        }
        return result;
    }

    public static JSONObject requestScan() {
        JSONObject result = status();
        if (!result.optBoolean("ready")) return result;
        long now = SystemClock.elapsedRealtime();
        if (now - lastScanAt < 400) return result;
        lastScanAt = now;
        scanState = "REQUESTED";
        scanMessage = "";
        try {
            if (printState.equals("PREPARING") || printState.equals("SENDING"))
                throw new IllegalStateException("Print job still sending");
            // 与 Demo 相同：先清缓冲，再 GPIO 167 上电，100ms 后发送扫描命令。
            PrintUtils.sb1.setLength(0);
            Qs608Receiver.beginScan();
            PrintUtils.mICommService.setGpioOut(167, 1);
            MAIN.postDelayed(() -> {
                try {
                    if (!status().optBoolean("ready") || printState.equals("PREPARING") || printState.equals("SENDING"))
                        throw new IllegalStateException("Hardware changed before delayed scan command");
                    Qs608Transport.enqueue(new byte[]{0x1B, 0x23, 0x23, 0x35, 0x35, 0x44, 0x4E}, () -> {
                        if (scanState.equals("REQUESTED")) scanState = "COMMAND_SENT";
                    });
                } catch (Exception cause) { failScan(cause); }
            }, 100);
        } catch (Exception cause) { failScan(cause); }
        return status();
    }

    private static void failScan(Exception cause) {
        scanState = "FAILED";
        scanMessage = "扫描指令未能发送，请等待打印结束或重启应用后重试";
        Log.e(TAG, "608 scan command failed", cause);
    }

    public static void onDecodedScan(Message message) {
        if (message.what != 0x123) return;
        // 旧插件在十六进制字符串上判断 ##55，解码后漏掉控制回包。
        PrintUtils.sb1.setLength(0);
        if (!Qs608Protocol.isBarcode(message.obj)) { ignoredFrames++; return; }
        String value = ((String) message.obj).trim();
        scanState = "DECODED";
        scanMessage = "";
        Intent result = new Intent("com.qs.scancode");
        result.setPackage(application.getPackageName());
        result.putExtra("data", value);
        application.sendBroadcast(result);
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 80);
        } catch (RuntimeException cause) {
            // 提示音失败不丢弃已经解出的条码。
            Log.w(TAG, "608 scan tone unavailable", cause);
        }
    }

    public static void onReceiveStatus(int value) {
        if (value == Qs608ReceiveBuffer.NO_PAPER) {
            PrintUtils.isCanprint = false;
            Message message = new Message(); message.what = -1;
            onPrinterMessage(message);
        } else if (value == Qs608ReceiveBuffer.READY) {
            PrintUtils.isCanprint = true;
        } else ignoredFrames++;
    }

    public static void onRejectedFrame(String reason) {
        ignoredFrames++;
        // 不记录条码内容；错误不会伪装成有效扫描结果。
        Log.w(TAG, "608 receive frame rejected: " + reason);
    }

    public static void onReceiveFailure(Exception cause) {
        receiveFailed = true;
        Qs608Transport.stop();
        ready = false;
        error = "608 串口接收已中断，请重启应用；不会提交未完整识别的条码";
        scanState = "FAILED"; scanMessage = error;
        if (printState.equals("PREPARING") || printState.equals("SENDING")) failPrint(cause);
        Log.e(TAG, "608 serial reader stopped", cause);
    }

    public static JSONObject preparePrint() {
        JSONObject result = status();
        if (!result.optBoolean("ready")) return result;
        if (printState.equals("PREPARING") || printState.equals("SENDING")) {
            try { result.put("ready", false); result.put("message", "上一张标签仍在发送，请等待并核对实物"); }
            catch (Exception cause) { throw new IllegalStateException(cause); }
            return result;
        }
        printSequence++;
        printState = "PREPARING";
        printMessage = "";
        return status();
    }

    public static void printBitmap(int paperWidth, int align, Bitmap bitmap, boolean label, boolean tearPaper) {
        final int sequence = printSequence;
        try {
            if (!printState.equals("PREPARING")) throw new IllegalStateException("Print request was not prepared");
            if (bitmap == null || bitmap.getWidth() > 570 || bitmap.getWidth() <= 0)
                throw new IllegalArgumentException("608 bitmap exceeds documented width");
            Qs608Transport.enqueue(new byte[]{0x1D, 0x61, 0x00}, () -> MAIN.postDelayed(() -> {
                if (sequence != printSequence || !printState.equals("PREPARING")) return;
                try {
                    if (!status().optBoolean("ready")) throw new IllegalStateException("Hardware disconnected before raster send");
                    if (!PrintUtils.isCanprint) {
                        printState = "FAILED";
                        printMessage = "打印机未报告可打印状态，请检查纸张、纸盖并重新自检；未发送标签点阵";
                        return;
                    }
                    byte[] raster = Qs608Protocol.rasterize(bitmap.getWidth(), bitmap.getHeight(), bitmap::getPixel);
                    byte[] frame = Qs608Protocol.framePrint(raster, label, tearPaper && !blackMark);
                    printState = "SENDING";
                    Qs608Transport.send(new byte[]{0x1B, 0x40});
                    Qs608Transport.send(new byte[]{0x1B, 0x61, (byte) align});
                    Qs608Transport.enqueue(frame, () -> {
                        if (sequence == printSequence && printState.equals("SENDING")) {
                            printState = "SENT";
                            printMessage = "点阵已写入串口，实际出纸仍需核对";
                        }
                    });
                } catch (Exception cause) { failPrint(cause); }
            }, 200));
        } catch (Exception cause) { failPrint(cause); }
    }

    public static void failPrint(Exception cause) {
        printState = "FAILED";
        printMessage = "打印处理失败，可能已部分发送，请核对实物；不会自动重打";
        Log.e(TAG, "608 print failed, sequence=" + printSequence, cause);
    }

    public static void onPrinterMessage(Message message) {
        if (message.what == -1 && (printState.equals("PREPARING") || printState.equals("SENDING") || printState.equals("SENT"))) {
            printState = "FAILED";
            printMessage = "设备报告缺纸，请检查纸张并核对是否已部分出纸；不会自动重打";
        }
    }

    public static void onTransportFailure(Exception cause) {
        receiveFailed = true;
        ready = false;
        error = "608 串口发送失败或设备持续忙，请核对实物并重启应用；不会自动重发";
        scanState = "FAILED"; scanMessage = error;
        if (printState.equals("PREPARING") || printState.equals("SENDING")) failPrint(cause);
        Log.e(TAG, "608 serial queue stopped", cause);
    }
}
