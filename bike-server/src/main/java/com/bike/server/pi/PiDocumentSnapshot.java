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
        @Size(max = 100) String sellerCountry,
        @Size(max = 100) String buyerContactName,
        @Size(max = 64) String buyerPhone,
        @jakarta.validation.constraints.Email @Size(max = 254) String buyerEmail,
        @Size(max = 150) String businessOwner,
        @Size(max = 100) String sellerContactName,
        @Size(max = 64) String sellerPhone,
        @jakarta.validation.constraints.Email @Size(max = 254) String sellerEmail) {
    public PiDocumentSnapshot(String sellerName,String sellerAddress,String sellerContact,String buyerAddress,String buyerContact,
            String bankName,String bankAccount,String beneficiary,String paymentTerms,String deliveryTerms,String templateCode,String templateVersion,
            LocalDate issueDate,LocalDate validUntil,String shippingAddress,String transportMode,String tradeTerms,String buyerCountry,String sellerCountry,
            String buyerContactName,String buyerPhone,String buyerEmail,String businessOwner) {
        this(sellerName,sellerAddress,sellerContact,buyerAddress,buyerContact,bankName,bankAccount,beneficiary,paymentTerms,deliveryTerms,
                templateCode,templateVersion,issueDate,validUntil,shippingAddress,transportMode,tradeTerms,buyerCountry,sellerCountry,
                buyerContactName,buyerPhone,buyerEmail,businessOwner,null,null,null);
    }
    public PiDocumentSnapshot(String sellerName,String sellerAddress,String sellerContact,String buyerAddress,String buyerContact,
            String bankName,String bankAccount,String beneficiary,String paymentTerms,String deliveryTerms,String templateCode,String templateVersion,
            LocalDate issueDate,LocalDate validUntil,String shippingAddress,String transportMode,String tradeTerms,String buyerCountry,String sellerCountry) {
        this(sellerName,sellerAddress,sellerContact,buyerAddress,buyerContact,bankName,bankAccount,beneficiary,paymentTerms,deliveryTerms,
                templateCode,templateVersion,issueDate,validUntil,shippingAddress,transportMode,tradeTerms,buyerCountry,sellerCountry,null,null,null,null);
    }
    /** Historical JSON and old drafts keep unknown dates and terms empty. */
    public PiDocumentSnapshot(String sellerName,String sellerAddress,String sellerContact,String buyerAddress,String buyerContact,
            String bankName,String bankAccount,String beneficiary,String paymentTerms,String deliveryTerms,String templateCode,String templateVersion) {
        this(sellerName,sellerAddress,sellerContact,buyerAddress,buyerContact,bankName,bankAccount,beneficiary,paymentTerms,deliveryTerms,
                templateCode,templateVersion,null,null,null,null,null,null,null);
    }
}
