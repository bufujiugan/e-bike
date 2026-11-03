package com.sdk;

interface ICommService {
	int setGpioOut(int pin, int iout);
	int getGpioPrinterState();
	boolean CtrlPrinterOn(boolean isOn);
}
