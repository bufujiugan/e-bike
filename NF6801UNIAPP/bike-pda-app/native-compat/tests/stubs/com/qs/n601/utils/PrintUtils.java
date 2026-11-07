package com.qs.n601.utils;
import java.io.OutputStream;
public final class PrintUtils {
    public interface Service { int getGpioPrinterState() throws Exception; }
    public static Service mICommService;
    public static OutputStream mOutputStream;
}
