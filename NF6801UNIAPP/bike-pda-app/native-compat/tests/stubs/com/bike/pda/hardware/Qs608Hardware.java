package com.bike.pda.hardware;
public final class Qs608Hardware {
    public static Exception failure;
    public static void onTransportFailure(Exception cause) { failure = cause; }
}
