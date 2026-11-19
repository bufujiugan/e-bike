package com.bike.server.order;

public interface OrderContractService {
    Preparation prepareContract(long orderId);
    byte[] generateContract(long orderId, OrderContractRequest request);

    record Preparation(boolean available, String reason, OrderContractRequest contract, boolean hasLinkedPi) {
        public Preparation(boolean available, String reason, OrderContractRequest contract) {
            this(available, reason, contract, false);
        }
    }
}
