package com.bike.server.aftersales;

import java.time.LocalDateTime;
public record AfterSalesCompanyView(Long agentId, String companyName, String contactName, String contactPhone,
    String contactEmail, String countryCode, String region, String city, String addressLine, String postalCode,
    String source, LocalDateTime updatedAt) {
    public AfterSalesContact contact() { return new AfterSalesContact(contactName,contactPhone,contactEmail,countryCode,region,city,addressLine,postalCode); }
}
