package com.bike.server.aftersales;

import com.bike.server.common.BusinessException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;

/** User filters never carry the authenticated company or salesperson scope. */
public record AfterSalesCaseFilter(String keyword, String status, String companyName, String contactName,
    String frameSn, String modelName, String faultType, String countryCode, String assigneeName,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo, String queue) {
    private static final Set<String> STATUSES = Set.of("DRAFT", "SUBMITTED", "UNDER_REVIEW", "NEEDS_INFO",
        "APPROVED", "IN_PROGRESS", "WAITING_PARTS", "SHIPPED", "RESOLVED", "CLOSED", "REJECTED");
    private static final Set<String> QUEUES = Set.of("PENDING", "REVIEW", "WAITING_CUSTOMER", "PROCESSING",
        "WAITING_SHIPMENT", "SHIPPED", "COMPLETED", "COMPLETED_MONTH");

    public static AfterSalesCaseFilter basic(String keyword, String status) {
        return new AfterSalesCaseFilter(keyword, status, null, null, null, null, null, null, null, null, null, null);
    }

    public AfterSalesCaseFilter normalized() {
        String normalizedStatus = text(status, 32), normalizedQueue = text(queue, 32), country = text(countryCode, 2);
        if (normalizedStatus != null && !STATUSES.contains(normalizedStatus)) throw invalid("案例状态不合法");
        if (normalizedQueue != null && !QUEUES.contains(normalizedQueue)) throw invalid("快捷分类不合法");
        if (country != null) {
            country = country.toUpperCase(Locale.ROOT);
            if (!Set.of(Locale.getISOCountries()).contains(country)) throw invalid("国家须为有效的两位国家代码");
        }
        if ((createdFrom != null && createdFrom.getYear() < 1000)
            || (createdTo != null && createdTo.getYear() >= 9999)) throw invalid("查询日期超出有效范围");
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) throw invalid("起始日期不能晚于结束日期");
        return new AfterSalesCaseFilter(text(keyword, 255), normalizedStatus, text(companyName, 255),
            text(contactName, 100), text(frameSn, 128), text(modelName, 100), text(faultType, 64), country,
            text(assigneeName, 100), createdFrom, createdTo, normalizedQueue);
    }

    public AfterSalesCaseFilter withoutStatusAndQueue() {
        return new AfterSalesCaseFilter(keyword, null, companyName, contactName, frameSn, modelName, faultType,
            countryCode, assigneeName, createdFrom, createdTo, null);
    }

    private static String text(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl)) throw invalid("查询条件过长或包含控制字符");
        return normalized;
    }

    // Literal contains search: '%' and '_' in customer input are not wildcard instructions.
    public static String like(String value) { return value == null ? null : "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"; }
    public String keywordLike() { return like(keyword); }
    public String companyNameLike() { return like(companyName); }
    public String contactNameLike() { return like(contactName); }
    public String frameSnLike() { return like(frameSn); }
    public String modelNameLike() { return like(modelName); }
    public String assigneeNameLike() { return like(assigneeName); }
    public LocalDate createdBefore() { return createdTo == null ? null : createdTo.plusDays(1); }
    private static BusinessException invalid(String message) { return new BusinessException("INVALID_PARAMETER", message); }
}
