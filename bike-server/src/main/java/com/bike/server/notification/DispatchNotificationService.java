package com.bike.server.notification;

import com.bike.server.common.PageResponse;
import com.bike.server.pi.PiDispatchBatch;
import com.bike.server.stock.StockRecordView;

public interface DispatchNotificationService {
    record Pending(long unreadCount, DispatchNotification nextNotification) {}

    void recordOrderDispatch(StockRecordView firstRecord, int quantity);

    void recordPiDispatch(PiDispatchBatch batch, String piNo);

    PageResponse<DispatchNotification> list(boolean unreadOnly, int page);

    Pending pending(long afterId);

    void markRead(long id);
}
