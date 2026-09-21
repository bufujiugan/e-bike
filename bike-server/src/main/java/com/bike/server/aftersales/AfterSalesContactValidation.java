package com.bike.server.aftersales;

import com.bike.server.common.BusinessException;
import java.util.Locale;
import java.util.Set;
import java.time.LocalDate;

final class AfterSalesContactValidation {
    private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());
    private AfterSalesContactValidation() {}
    static AfterSalesContact normalize(AfterSalesContact input) {
        if (input == null) return null;
        String email = value(input.contactEmail(),254), country = value(input.countryCode(),2);
        if (email != null && !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw invalid("Email格式不正确");
        if (country != null) { country = country.toUpperCase(Locale.ROOT); if (!COUNTRIES.contains(country)) throw invalid("请选择有效的两位国家代码"); }
        return new AfterSalesContact(value(input.contactName(),100),value(input.contactPhone(),64),email,country,
            value(input.region(),100),value(input.city(),100),value(input.addressLine(),500),value(input.postalCode(),32));
    }
    static String value(String input, int max) {
        if (input == null || input.isBlank()) return null;
        String result = input.trim();
        if (result.length() > max || result.chars().anyMatch(Character::isISOControl)) throw invalid("联系资料长度超限或包含控制字符");
        return result;
    }
    static LocalDate date(LocalDate value) { if (value != null && value.isAfter(LocalDate.now())) throw invalid("最终客户购买日期不能在未来"); return value; }
    private static BusinessException invalid(String message) { return new BusinessException("INVALID_PARAMETER",message); }
}
