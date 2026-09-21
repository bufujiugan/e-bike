package com.bike.server.aftersales;

public record AfterSalesContact(String contactName, String contactPhone, String contactEmail, String countryCode,
    String region, String city, String addressLine, String postalCode) {}
