package com.bike.server.pi;

import com.bike.server.master.CustomerProfileService;
import org.springframework.stereotype.Service;

/** Defaults are captured once at creation. Reading, editing and exporting never refresh historical parties. */
@Service
public class PiCustomerSnapshotService {
    private final CustomerProfileService customers;
    public PiCustomerSnapshotService(CustomerProfileService customers){this.customers=customers;}
    public PiDocumentSnapshot capture(long customerId,PiDocumentSnapshot input) {
        var p=customers.get(customerId);
        var d=input==null?new PiDocumentSnapshot(null,null,null,null,null,null,null,null,null,null,null,null):input;
        String contact=java.util.stream.Stream.of(p.contactName(),p.contactPhone()).filter(x->x!=null&&!x.isBlank()).collect(java.util.stream.Collectors.joining(" / "));
        return new PiDocumentSnapshot(d.sellerName(),d.sellerAddress(),d.sellerContact(),value(d.buyerAddress(),p.address()),value(d.buyerContact(),contact),
                d.bankName(),d.bankAccount(),d.beneficiary(),d.paymentTerms(),d.deliveryTerms(),d.templateCode(),d.templateVersion(),
                d.issueDate(),d.validUntil(),value(d.shippingAddress(),p.address()),d.transportMode(),d.tradeTerms(),value(d.buyerCountry(),p.country()),d.sellerCountry(),
                value(d.buyerContactName(),p.contactName()),value(d.buyerPhone(),p.contactPhone()),value(d.buyerEmail(),p.email()),value(d.businessOwner(),p.businessOwner()));
    }
    private static String value(String specified,String fallback){return specified==null||specified.isBlank()?fallback:specified;}
}
