package com.bike.server.trace;

public interface TraceService {
    TraceResponse traceQr(String qrCode);
    TraceResponse traceFrame(String frameSn);
    TraceResponse tracePart(String partSn);
}
