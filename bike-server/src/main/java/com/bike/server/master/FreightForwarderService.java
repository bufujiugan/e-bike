package com.bike.server.master;

import java.util.List;

public interface FreightForwarderService {
    List<FreightForwarder> listForwarders();

    FreightForwarder createForwarder(FreightForwarderRequest request);

    FreightForwarder updateForwarder(long id, int revision, FreightForwarderRequest request);
}
