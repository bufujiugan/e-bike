package com.bike.server.pi;

import java.util.Arrays;

public enum PiWorkflowStepCode {
    PI_CREATED(1, "确认出库仓库并建立PI"),
    DOCUMENTS_READY(3, "确认PI、销售合同及收付款表"),
    PAYMENT_CONFIRMED(2, "确认客户按合同付款"),
    INVENTORY_RESERVED(9, "锁定指定仓库销售库存"),
    PURCHASE_CONTRACT_READY(10, "确认出库所需购销合同"),
    STOCK_DISPATCHED(11, "等待并办理出库"),
    FORWARDER_SELECTED(5, "确认货代责任方及收货信息"),
    DOMESTIC_EXPRESS_BOOKED(6, "登记送仓承运商、单号及运费"),
    WAREHOUSE_ARRIVED(12, "确认到达货代仓库并保留凭证"),
    TRACKING_RECORDED(7, "登记货代发货号、费用并跟踪运输"),
    CUSTOMER_RECEIVED(8, "确认全部货物签收，完成订单"),
    PRODUCTION_FOLLOW_UP(4, "生产跟进（可选）");

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
