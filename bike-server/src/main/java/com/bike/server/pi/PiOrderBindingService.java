package com.bike.server.pi;

public interface PiOrderBindingService {
    void requireConfirmableBinding(PiView pi);
    void requireConsistentFulfillment(PiView pi);
}
