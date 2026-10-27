package com.bike.server.pi;

import java.util.Arrays;

public enum PiWorkflowStepCode {
    PI_CREATED(1, "建立 PI"),
    PAYMENT_CONFIRMED(2, "等待并确认客户付款"),
    DOCUMENTS_READY(3, "建立合同、收付款、生产表"),
    PRODUCTION_FOLLOW_UP(4, "跟进工厂生产进度"),
    FORWARDER_SELECTED(5, "联系货代询价并选择货代"),
    DOMESTIC_EXPRESS_BOOKED(6, "预约工厂至货代的快递"),
    TRACKING_RECORDED(7, "填写单号并跟踪运输"),
    CUSTOMER_RECEIVED(8, "确认客户收货，完成订单");

    private final int order;
    private final String name;

    PiWorkflowStepCode(int order, String name) {
        this.order = order;
        this.name = name;
    }

    public int order() { return order; }
    public String stepName() { return name; }

    public static PiWorkflowStepCode parse(String value) {
        if (value == null) return null;
        return Arrays.stream(values()).filter(item -> item.name().equalsIgnoreCase(value.trim())).findFirst().orElse(null);
    }
}
