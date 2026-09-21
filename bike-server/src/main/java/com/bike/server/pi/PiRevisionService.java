package com.bike.server.pi;

import java.util.List;

public interface PiRevisionService {
    PiView create(long piId, PiRevisionRequest request);
    List<PiView> history(long piId);
}
