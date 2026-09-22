package com.bike.server.aftersales;

public record AfterSalesLogisticsRequest(int revision,String action,AfterSalesShippingDetails shipping,
    String carrier,String trackingNo,String logisticsStatus,String note,java.time.LocalDate expectedArrivalDate) {
    public AfterSalesLogisticsRequest(int revision,String action,AfterSalesShippingDetails shipping,String carrier,String trackingNo,String logisticsStatus,String note) {
        this(revision,action,shipping,carrier,trackingNo,logisticsStatus,note,null);
    }
}
