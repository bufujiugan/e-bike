package com.bike.server.order;

import com.bike.server.common.BusinessException;
import java.util.Set;

/**
 * 销售订单的销售去向。由经办人在转单、建单或首次办理时明确选择，选定后不可修改。
 * <ul>
 *   <li>DOMESTIC：销售订单 → 内销，生成国内购销合同；</li>
 *   <li>EXPORT：销售订单 → 外销，使用 260728英国builtPI 模板办理 PI；</li>
 *   <li>EUROPE：欧洲销售与交付，使用 France_1104_PI 模板办理 PI。</li>
 * </ul>
 * 数据库为空表示历史订单尚未选择类型（待选类型）。
 */
public final class SalesType {
    public static final String DOMESTIC = "DOMESTIC";
    public static final String EXPORT = "EXPORT";
    public static final String EUROPE = "EUROPE";
    /** 仅用于列表筛选，表示尚未选择销售类型的历史订单。 */
    public static final String PENDING = "PENDING";
    public static final Set<String> VALUES = Set.of(DOMESTIC, EXPORT, EUROPE);

    private SalesType() {}

    public static String require(String value) {
        if (value == null || !VALUES.contains(value)) {
            throw new BusinessException("ORDER_SALES_TYPE_INVALID", "请选择销售类型：内销购销合同、外销 PI 或欧洲 PI");
        }
        return value;
    }

    /**
     * 按 PI 快照判断销售类型，规则与订单列表 SQL（OrderMapper.xml effectiveSalesType）一致：
     * 销售入口标识优先；没有标识的历史 PI 以非国内出货视为欧洲，其余按贸易类型区分。
     */
    public static String ofPi(String tradeType, String salesChannel, Boolean domesticShipment) {
        if ("CZECH_PI".equals(salesChannel)) return EUROPE;
        if ("CHINA_SALES".equals(salesChannel)) return EXPORT;
        if (Boolean.FALSE.equals(domesticShipment)) return EUROPE;
        return "EXPORT".equals(tradeType) ? EXPORT : DOMESTIC;
    }

    /** 解析列表筛选参数（逗号分隔，可含 PENDING）；为空返回 null 表示不筛选。 */
    public static java.util.List<String> filter(String salesTypes) {
        if (salesTypes == null || salesTypes.isBlank()) return null;
        var values = java.util.Arrays.stream(salesTypes.split(",")).map(String::trim).filter(v -> !v.isEmpty()).distinct().toList();
        for (String value : values) {
            if (!PENDING.equals(value) && !VALUES.contains(value)) {
                throw new BusinessException("ORDER_SALES_TYPE_INVALID", "销售类型筛选无效");
            }
        }
        return values.isEmpty() ? null : values;
    }

    public static String label(String value) {
        if (DOMESTIC.equals(value)) return "内销 · 购销合同";
        if (EXPORT.equals(value)) return "外销 · PI";
        if (EUROPE.equals(value)) return "欧洲 · PI";
        return "待选类型";
    }
}
