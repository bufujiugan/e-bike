package com.qs.n601.utils;
import com.sdk.ICommService;
import com.bike.pda.hardware.Qs608Transport;
import android.content.Context;
import java.io.*;
public class PrintUtils {
    public static ICommService mICommService;
    public static InputStream mInputStream; public static OutputStream mOutputStream;
    public static boolean isCanprint; public static final StringBuffer sb1=new StringBuffer();
    public static int initializations; public static Thread reader;
    public static void initPrintUtils(Context context) {
        initializations++;
        try {
            var constructor=Class.forName("com.qs.n601.utils.PrintUtils$ReadThread").getDeclaredConstructor();
            constructor.setAccessible(true); reader=(Thread)constructor.newInstance();
            reader.setDaemon(true); reader.start();
        } catch(Exception cause) { throw new IllegalStateException(cause); }
    }
    public static void open() { Qs608Transport.send(new byte[]{27,35,35,53,54,85,80}); }
    public static void labelEnable(boolean enabled) {}
    public static void setStarNum(int offset) {}
}
