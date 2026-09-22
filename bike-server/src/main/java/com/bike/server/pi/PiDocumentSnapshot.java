package com.bike.server.pi;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;

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
        @Size(max = 32) String templateVersion,
        LocalDate issueDate,
        LocalDate validUntil,
        @Size(max = 1000) String shippingAddress,
        @Size(max = 100) String transportMode,
        @Size(max = 100) String tradeTerms,
        @Size(max = 100) String buyerCountry,
        @Size(max = 100) String sellerCountry) {
    /** Historical JSON and old drafts keep unknown dates and terms empty. */
    public PiDocumentSnapshot(String sellerName,String sellerAddress,String sellerContact,String buyerAddress,String buyerContact,
            String bankName,String bankAccount,String beneficiary,String paymentTerms,String deliveryTerms,String templateCode,String templateVersion) {
        this(sellerName,sellerAddress,sellerContact,buyerAddress,buyerContact,bankName,bankAccount,beneficiary,paymentTerms,deliveryTerms,
                templateCode,templateVersion,null,null,null,null,null,null,null);
    }
}
