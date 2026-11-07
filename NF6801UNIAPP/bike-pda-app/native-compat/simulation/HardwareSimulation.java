import android.content.*;
import android.os.*;
import android.graphics.Bitmap;
import android.media.ToneGenerator;
import com.bike.pda.hardware.*;
import com.qs.n601.utils.PrintUtils;
import com.sdk.ICommService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class HardwareSimulation {
    private static final Context APP=new Context();
    private static final Feed INPUT=new Feed();
    private static final ByteArrayOutputStream OUTPUT=new ByteArrayOutputStream();
    private static final List<String> GPIO=new ArrayList<>();
    private static boolean connected=true;
    private static int busy,transactions,writes;
    private static String scenario;
    private static class Feed extends InputStream {
        final BlockingQueue<byte[]> queue=new LinkedBlockingQueue<>();
        final Semaphore waiting=new Semaphore(0);
        public int read() { throw new AssertionError("Expected buffer read"); }
        public int read(byte[] target) throws IOException {
            waiting.release();
            try { byte[] bytes=queue.take(); if(bytes.length==0) throw new IOException("Injected read failure");
                System.arraycopy(bytes,0,target,0,bytes.length); return bytes.length;
            } catch(InterruptedException cause) { Thread.currentThread().interrupt(); throw new IOException(cause); }
        }
        void send(byte[] bytes) throws Exception {
            queue.add(bytes);
            if(!waiting.tryAcquire(2,TimeUnit.SECONDS)) throw new AssertionError("Reader did not return to read");
            Handler.advance(0);
        }
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void receive(String value) throws Exception { INPUT.send(bytes(value)); }
    private static void setup() throws Exception {
        Looper.getMainLooper();
        PrintUtils.mInputStream=INPUT;
        PrintUtils.mOutputStream=new OutputStream() {
            public void write(int value) {}
            public void write(byte[] bytes,int offset,int length) throws IOException {
                writes++; if(scenario.equals("write-failure") && writes>1) throw new IOException("Injected write failure");
                OUTPUT.write(bytes,offset,length);
            }
        };
        IBinder binder=new IBinder() {
            public boolean transact(int code,Parcel request,Parcel response,int flags) {
                check(code==3 && request.token.equals("com.sdk.ICommService") && request.value==0,"Wrong power transaction");
                transactions++; response.value=1; return true;
            }
            public boolean pingBinder() { return connected; }
        };
        ICommService service=new ICommService() {
            public int setGpioOut(int pin,int value) { GPIO.add(pin+":"+value); return 0; }
            public int getGpioPrinterState() { return busy; }
            public IBinder asBinder() { return binder; }
        };
        if(!scenario.equals("service-timeout")) PrintUtils.mICommService=service;
        Qs608Hardware.open(APP,true,52);
        check(INPUT.waiting.tryAcquire(2,TimeUnit.SECONDS),"Patched reader did not start");
        check(!Qs608Hardware.status().optBoolean("ready"),"Premature ready");
        Handler.advance(2699); check(!Qs608Hardware.status().optBoolean("ready"),"Init delay bypassed");
        Handler.advance(1);
    }
    public static void main(String[] args) throws Exception {
        scenario=args[0]; setup();
        if(scenario.equals("service-timeout")) {
            Handler.advance(10000); check(!Qs608Hardware.status().optBoolean("ready") && !Qs608Hardware.status().optBoolean("initializing"),"Service timeout hidden");
        } else {
            check(Qs608Hardware.status().optBoolean("ready"),"Hardware not ready");
            check(transactions==1 && GPIO.containsAll(Arrays.asList("72:1","150:1","151:1","158:1")),"Power sequence mismatch");
            Qs608Hardware.open(APP,true,52); check(PrintUtils.initializations==1,"Duplicate reader");
            switch(scenario) {
                case "scan": scan(); break;
                case "read-failure":
                    receive("PARTIAL"); INPUT.queue.add(new byte[0]); PrintUtils.reader.join(2000); Handler.advance(500);
                    check(APP.broadcasts.isEmpty() && !Qs608Hardware.status().optBoolean("ready") && Qs608Hardware.status().optString("scanState").equals("FAILED"),"Partial read emitted on failure"); break;
                case "write-failure":
                    Qs608Hardware.requestScan(); Handler.advance(100);
                    check(!Qs608Hardware.status().optBoolean("ready") && Qs608Hardware.status().optString("scanState").equals("FAILED"),"Write failure hidden"); break;
                case "busy":
                    busy=1; Qs608Hardware.requestScan(); Handler.advance(10200);
                    check(!Qs608Hardware.status().optBoolean("ready") && writes==1,"Busy request sent or hidden"); break;
                case "disconnect":
                    connected=false; check(!Qs608Hardware.requestScan().optBoolean("ready"),"Disconnected service accepted scan"); break;
                case "print": print(); break;
                case "scan-print-race":
                    INPUT.send(new byte[]{0x14,0,0,0x0F});
                    int previous=OUTPUT.size();
                    Qs608Hardware.requestScan(); Qs608Hardware.preparePrint(); Handler.advance(100);
                    check(OUTPUT.size()==previous && Qs608Hardware.status().optString("scanState").equals("FAILED"),"Delayed scan interleaved with prepared print"); break;
                case "read-failure-print":
                    INPUT.send(new byte[]{0x14,0,0,0x0F}); busy=1;
                    Qs608Hardware.preparePrint(); Qs608Hardware.printBitmap(80,0,new Bitmap(570,99),true,false);
                    int queued=OUTPUT.size();
                    INPUT.queue.add(new byte[0]); PrintUtils.reader.join(2000); Handler.advance(0);
                    busy=0; Handler.advance(500);
                    check(OUTPUT.size()==queued && Qs608Hardware.status().optString("printState").equals("FAILED"),"Print queue continued after reader failed"); break;
                case "physical-key":
                    int beforeKey=OUTPUT.size();
                    BroadcastReceiver key=APP.receivers.get("qsapi.intent.key_scan_down");
                    check(key!=null,"Hardware key receiver missing");
                    key.onReceive(APP,new Intent("qsapi.intent.key_scan_down"));
                    key.onReceive(APP,new Intent("qsapi.intent.key_scan_down"));
                    Handler.advance(100); check(OUTPUT.size()==beforeKey+7,"Bounce emitted duplicate scan command");
                    receive("KEY-SCAN\r\n"); check(APP.broadcasts.size()==1,"Physical key scan lost"); break;
                case "control-only":
                    Qs608Hardware.requestScan(); Handler.advance(100);
                    receive("##55UP\r\n##56UP\r\n"); Handler.advance(1000);
                    check(APP.broadcasts.isEmpty() && ToneGenerator.sounds==0 && !Qs608Hardware.status().optString("scanState").equals("DECODED"),"Control frame false success"); break;
                default: throw new AssertionError("Unknown scenario");
            }
        }
        System.out.println("PASS actual AAR hardware/reader/transport simulation: "+scenario);
    }
    private static void scan() throws Exception {
        int start=OUTPUT.size(); Qs608Hardware.requestScan();
        check(GPIO.contains("167:1"),"Scan power missing"); Handler.advance(99); check(OUTPUT.size()==start,"Scan delay missing");
        Handler.advance(1); check(Arrays.equals(Arrays.copyOfRange(OUTPUT.toByteArray(),start,OUTPUT.size()),new byte[]{27,35,35,53,53,68,78}),"Wrong scan command");
        receive("##55UP\r\n"); Handler.advance(40); receive("BIKE-A\r\n");
        check(APP.broadcasts.size()==1 && APP.broadcasts.get(0).getStringExtra("data").equals("BIKE-A"),"Original coalescing bug");
        check(Qs608Hardware.status().optString("scanState").equals("DECODED"),"Missing decoded state");
        for(byte value: bytes("##55UP\r\n车辆-001\r\n")) INPUT.send(new byte[]{value});
        receive("BIKE-B\r\n##55UP\r\nBIKE-C\r\n");
        for(int index=0;index<200;index++) receive("##55UP\r\nBIKE-"+index+"\r\n");
        check(APP.broadcasts.size()==204 && ToneGenerator.sounds==204,"Lost/false/duplicate broadcast");
        for(Intent intent: APP.broadcasts) check(intent.action.equals("com.qs.scancode") && intent.targetPackage.equals("com.bike.pda"),"Broadcast routing");
        receive("OLD-PARTIAL"); Handler.advance(500);
        receive("STALE"); Handler.advance(400); // 已完成的无后缀码允许空闲结束。
        receive("CANCEL"); Qs608Receiver.beginScan(); Handler.advance(300);
        check(!APP.broadcasts.get(APP.broadcasts.size()-1).getStringExtra("data").equals("CANCEL"),"Canceled partial emitted");
        int before=APP.broadcasts.size(); receive("IDLE-CODE"); Handler.advance(199); check(APP.broadcasts.size()==before,"Premature idle decode");
        Handler.advance(1); check(APP.broadcasts.size()==before+1,"Idle decode missing");
        List<String> codes=new ArrayList<>(); for(Intent intent:APP.broadcasts) codes.add(intent.getStringExtra("data"));
        System.out.println("SCAN_TRACE="+Base64.getEncoder().encodeToString(String.join("\n",codes).getBytes(StandardCharsets.UTF_8)));
    }
    private static void print() throws Exception {
        INPUT.send(new byte[]{0x14,0,0,0x0F});
        check(PrintUtils.isCanprint,"Ready report lost");
        Qs608Hardware.preparePrint(); Qs608Hardware.printBitmap(80,0,new Bitmap(570,99),true,false);
        Handler.advance(200); check(Qs608Hardware.status().optString("printState").equals("SENT"),"Print did not send");
        INPUT.send(new byte[]{0x14,0,0x0C,0x0F});
        check(!PrintUtils.isCanprint && Qs608Hardware.status().optString("printState").equals("FAILED"),"Missing paper ignored");
        int before=OUTPUT.size(); Qs608Hardware.preparePrint(); Qs608Hardware.printBitmap(80,0,new Bitmap(570,99),true,false);
        Handler.advance(200); check(Qs608Hardware.status().optString("printState").equals("FAILED") && OUTPUT.size()==before+3,"Missing paper sent raster");
        receive("##55UP\r\nBIKE-AFTER-PRINT\r\n"); check(APP.broadcasts.size()==1,"Printer status swallowed barcode");
    }
}
