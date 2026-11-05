package com.bike.server.order;

import com.bike.server.pi.PiView;

public interface PaymentPriorityService {
    void cancelUnfulfillableUnpaidOrders(PiView paidPi);
}
