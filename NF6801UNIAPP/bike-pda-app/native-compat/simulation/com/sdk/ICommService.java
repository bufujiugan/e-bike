package com.sdk;
import android.os.IBinder;
public interface ICommService { int setGpioOut(int pin,int value); int getGpioPrinterState(); IBinder asBinder(); }
