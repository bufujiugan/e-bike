package com.bike.server.pi;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;

/** 单张PI只绑定一个发货仓库；历史单据缺失信息时由业务补录，不推断。 */
public record PiBusinessTerms(
        @NotNull @Positive Long warehouseId,
        @NotNull Boolean domesticShipment,
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=200) String collectionEntity,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @Pattern(regexp = "EXISTING_ORDER|NEW_BUSINESS") String orderSource,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) @Positive Long fulfillmentProfileId,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) java.time.LocalDate paymentDueDate,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @Pattern(regexp="STANDARD|USER_CONFIRMED") String workflowMode,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @Pattern(regexp="CHINA_SALES|CZECH_PI") String salesChannel) {
    public PiBusinessTerms(Long warehouseId, Boolean domesticShipment, String collectionEntity, String orderSource,
            Long fulfillmentProfileId, java.time.LocalDate paymentDueDate, String workflowMode) {
        this(warehouseId, domesticShipment, collectionEntity, orderSource, fulfillmentProfileId, paymentDueDate, workflowMode, null);
    }
    public PiBusinessTerms(Long warehouseId, Boolean domesticShipment, String collectionEntity, String orderSource,
            Long fulfillmentProfileId, java.time.LocalDate paymentDueDate) {
        this(warehouseId,domesticShipment,collectionEntity,orderSource,fulfillmentProfileId,paymentDueDate,null);
    }
    public PiBusinessTerms(Long warehouseId, Boolean domesticShipment, String collectionEntity, String orderSource) {
        this(warehouseId, domesticShipment, collectionEntity, orderSource, null, null);
    }
    public PiBusinessTerms(Long warehouseId, Boolean domesticShipment, String collectionEntity) {
        this(warehouseId, domesticShipment, collectionEntity, null);
    }
}
