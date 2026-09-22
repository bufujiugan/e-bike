package com.bike.server.aftersales;

public record AfterSalesDashboardView(long total, long pending, long review, long waitingCustomer,
    long processing, long waitingShipment, long shipped, long completed, long completedThisMonth) {}
