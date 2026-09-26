package com.bike.server.aftersales;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record AfterSalesCaseCreateRequest(
        @Positive(message = "客户ID必须大于0") Long customerId,
        @Positive(message = "代理商ID必须大于0") Long agentId,
        @Positive(message = "车辆ID必须大于0") Long vehicleId,
        @Size(max = 128, message = "车架号长度不能超过128") String frameSn,
        @Positive(message = "车型ID必须大于0") Long productModelId,
        @Size(max = 128, message = "电机序列号长度不能超过128") String motorSn,
        @Size(max = 128, message = "电池序列号长度不能超过128") String batterySn,
        @Size(max = 128, message = "仪表序列号长度不能超过128") String displaySn,
        LocalDate purchaseDate,
        LocalDateTime failureTime,
        @Size(max = 64, message = "故障类型长度不能超过64") String faultType,
        @Size(max = 128, message = "故障部件长度不能超过128") String faultPart,
        @Size(max = 128, message = "故障代码长度不能超过128") String errorCode,
        @NotBlank(message = "问题描述不能为空") @Size(max = 5000, message = "问题描述长度不能超过5000") String description,
        @Size(max = 2000, message = "已尝试维修方法长度不能超过2000") String attemptedRepair,
        @Size(max = 32, message = "保修状态长度不能超过32") String warrantyStatus,
        AfterSalesContact contact,
        @Size(max = 200, message = "聚合码长度不能超过200") String vehicleQrCode,
        LocalDate endCustomerPurchaseDate
) {
    public AfterSalesCaseCreateRequest(Long customerId,Long agentId,Long vehicleId,String frameSn,Long productModelId,
        String motorSn,String batterySn,String displaySn,LocalDate purchaseDate,LocalDateTime failureTime,String faultType,
        String faultPart,String errorCode,String description,String attemptedRepair,String warrantyStatus,
        AfterSalesContact contact,LocalDate endCustomerPurchaseDate) {
        this(customerId,agentId,vehicleId,frameSn,productModelId,motorSn,batterySn,displaySn,purchaseDate,failureTime,faultType,
            faultPart,errorCode,description,attemptedRepair,warrantyStatus,contact,null,endCustomerPurchaseDate);
    }
    public AfterSalesCaseCreateRequest(Long customerId,Long agentId,Long vehicleId,String frameSn,Long productModelId,
        String motorSn,String batterySn,String displaySn,LocalDate purchaseDate,LocalDateTime failureTime,String faultType,
        String faultPart,String errorCode,String description,String attemptedRepair,String warrantyStatus) {
        this(customerId,agentId,vehicleId,frameSn,productModelId,motorSn,batterySn,displaySn,purchaseDate,failureTime,faultType,
            faultPart,errorCode,description,attemptedRepair,warrantyStatus,null,null);
    }
}
