package com.bike.server.pi;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PiWorkflowDetails(
        String contractNo,
        Boolean contractReady,
        Boolean paymentTableReady,
        Boolean productionTableReady,
        String productionStatus,
        LocalDate expectedCompletionDate,
        String materialStatus,
        String forwarderName,
        BigDecimal quotedAmount,
        String quoteCurrency,
        String domesticCarrier,
        BigDecimal domesticPrice,
        String domesticTrackingNo,
        LocalDate receivedDate,
        String purchaseContractNo, String purchaseContractParty,
        String forwarderResponsibility, String forwarderAddress, String forwarderContact,
        LocalDate warehouseArrivalDate, String arrivalProof,
        String internationalTrackingNo, BigDecimal internationalPrice, String internationalCurrency) {
    public PiWorkflowDetails(String contractNo,Boolean contractReady,Boolean paymentTableReady,Boolean productionTableReady,
            String productionStatus,LocalDate expectedCompletionDate,String materialStatus,String forwarderName,BigDecimal quotedAmount,
            String quoteCurrency,String domesticCarrier,BigDecimal domesticPrice,String domesticTrackingNo,LocalDate receivedDate) {
        this(contractNo,contractReady,paymentTableReady,productionTableReady,productionStatus,expectedCompletionDate,materialStatus,
                forwarderName,quotedAmount,quoteCurrency,domesticCarrier,domesticPrice,domesticTrackingNo,receivedDate,
                null,null,null,null,null,null,null,null,null,null);
    }
}
