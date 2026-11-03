package com.bike.server.pi;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface PiBusinessService {
    List<Map<String,Object>> warehouses();
    PiDocumentSnapshot document(long piId);
    void validateTerms(PiDocumentSnapshot document,String tradeType);
    void requireDocuments(long piId);
    void requireContract(long piId);
    boolean reserveAfterPayment(PiView pi,BigDecimal paid);
    boolean hasReservedStock(PiView pi);
    void validateReservedWarehouse(PiView pi);
    void requireShipment(long piId);
    void requireVehicle(long piId,long vehicleId);
    void requireWarehouseCapacity(long vehicleId,Long orderId);
    void invalidateDelivery(long piId);
    void invalidateDeliveryForOrder(long orderId);
    boolean allDispatched(long piId);
    boolean allTracked(long piId);
    boolean isCustomerForwarder(long piId);
    void requireArrivalProof(long piId,String attachmentId);
}
