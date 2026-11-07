package android.util;
public class Log {
    public static int e(String tag,String message,Throwable cause) { if(cause==null) throw new AssertionError("Missing exception"); return 0; }
    public static int w(String tag,String message,Throwable cause) { return 0; }
    public static int w(String tag,String message) { return 0; }
    public static int e(String tag,String message) { return 0; }
    public static int i(String tag,String message) { return 0; }
}
