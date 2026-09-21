package com.bike.server.pi;

import jakarta.validation.constraints.Size;

/** Parties, payment instructions and template identity captured when the PI is created. */
public record PiDocumentSnapshot(
        @Size(max = 200) String sellerName,
        @Size(max = 1000) String sellerAddress,
        @Size(max = 300) String sellerContact,
        @Size(max = 1000) String buyerAddress,
        @Size(max = 300) String buyerContact,
        @Size(max = 200) String bankName,
        @Size(max = 200) String bankAccount,
        @Size(max = 200) String beneficiary,
        @Size(max = 2000) String paymentTerms,
        @Size(max = 2000) String deliveryTerms,
        @Size(max = 64) String templateCode,
        @Size(max = 32) String templateVersion) {
}
