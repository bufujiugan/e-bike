package com.bike.server.aftersales;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import com.bike.server.common.BusinessException;

/** Separate recipient snapshot: later company/case edits never rewrite an existing shipment. */
public record AfterSalesShippingDetails(String warehouseName,String recipient,String phone,String countryCode,
    String region,String city,String addressLine,String postalCode,LocalDate expectedArrivalDate,LocalDateTime shippedAt) {
    static AfterSalesShippingDetails from(AfterSalesShipmentView s) {
        return new AfterSalesShippingDetails(s.getWarehouseName(),s.getRecipient(),s.getPhone(),s.getCountryCode(),s.getRegion(),s.getCity(),s.getAddressLine(),s.getPostalCode(),s.getExpectedArrivalDate(),s.getShippedAt());
    }
    static AfterSalesShippingDetails from(AfterSalesCaseView c) {
        return new AfterSalesShippingDetails(null,c.getContactName(),c.getContactPhone(),c.getCountryCode(),c.getRegion(),c.getCity(),c.getAddressLine(),c.getPostalCode(),null,null);
    }
    AfterSalesShippingDetails normalized(boolean dispatch) {
        String country=text(countryCode,2,dispatch);
        if(country!=null) {country=country.toUpperCase(Locale.ROOT);if(!Set.of(Locale.getISOCountries()).contains(country))throw invalid("收货国家代码不合法");}
        LocalDateTime shipped=dispatch?(shippedAt==null?LocalDateTime.now():shippedAt):null;
        if(!dispatch && shippedAt!=null)throw invalid("未发货计划不能填写实际发货时间");
        if(shipped!=null && (shipped.getYear()<2000 || shipped.isAfter(LocalDateTime.now())))throw invalid("实际发货时间不能为未来时间或早于2000年");
        if(expectedArrivalDate!=null && (expectedArrivalDate.getYear()<2000 || expectedArrivalDate.getYear()>9998 || (shipped!=null && expectedArrivalDate.isBefore(shipped.toLocalDate()))))throw invalid("预计到达日期不能早于发货日期");
        return new AfterSalesShippingDetails(text(warehouseName,128,dispatch),text(recipient,100,dispatch),text(phone,64,dispatch),country,
            text(region,100,false),text(city,100,false),text(addressLine,500,dispatch),text(postalCode,32,false),expectedArrivalDate,shipped);
    }
    static String text(String value,int max,boolean required) {
        String v=value==null?null:value.trim();if(v!=null&&v.isEmpty())v=null;
        if((required&&v==null)||(v!=null&&(v.length()>max||v.chars().anyMatch(Character::isISOControl))))throw invalid("请填写完整有效的仓库、收货和物流资料");
        return v;
    }
    static BusinessException invalid(String message){return new BusinessException("INVALID_PARAMETER",message);}
}
