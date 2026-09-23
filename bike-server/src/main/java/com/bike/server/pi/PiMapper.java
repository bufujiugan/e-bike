package com.bike.server.pi;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PiMapper {
    List<Map<String, Object>> selectCustomerOptions(@Param("keyword") String keyword);
    List<Map<String, Object>> selectAgentOptions(@Param("keyword") String keyword);
    List<Map<String, Object>> selectSkuOptions(@Param("keyword") String keyword);
    List<Map<String, Object>> selectOrderOptions(@Param("keyword") String keyword);
    List<PiView> selectPage(@Param("keyword") String keyword, @Param("status") String status,
            @Param("agentId") Long agentId, @Param("offset") long offset, @Param("limit") long limit);
    long count(@Param("keyword") String keyword, @Param("status") String status, @Param("agentId") Long agentId);
    PiView selectById(@Param("id") long id);
    PiView selectForUpdate(@Param("id") long id);
    PiView selectForSourceOrderForUpdate(@Param("orderId") long orderId);
    Long lockPi(@Param("id") long id);
    int insertPi(@Param("piNo") String piNo, @Param("request") PiCreateRequest request,
            @Param("tradeType") String tradeType, @Param("currencyCode") String currencyCode,
            @Param("subtotal") BigDecimal subtotal, @Param("discountAmount") BigDecimal discountAmount,
            @Param("taxAmount") BigDecimal taxAmount, @Param("freightAmount") BigDecimal freightAmount,
            @Param("totalAmount") BigDecimal totalAmount, @Param("depositRequiredAmount") BigDecimal depositRequiredAmount,
            @Param("shipmentRequiredAmount") BigDecimal shipmentRequiredAmount, @Param("documentJson") String documentJson,
            @Param("agentId") Long agentId, @Param("createdBy") Long createdBy);
    int insertItems(@Param("piId") long piId, @Param("items") List<PiCalculatedItem> items);
    int cloneRevision(@Param("piId") long piId, @Param("seriesId") long seriesId, @Param("revision") int revision,
            @Param("operatorId") long operatorId);
    int cloneItems(@Param("sourcePiId") long sourcePiId, @Param("targetPiId") long targetPiId);
    List<PiView> selectRevisions(@Param("seriesId") long seriesId);
    int deleteItems(@Param("piId") long piId);
    int updatePi(@Param("id") long id, @Param("request") PiCreateRequest request,
            @Param("tradeType") String tradeType, @Param("currencyCode") String currencyCode,
            @Param("subtotal") BigDecimal subtotal, @Param("discountAmount") BigDecimal discountAmount,
            @Param("taxAmount") BigDecimal taxAmount, @Param("freightAmount") BigDecimal freightAmount,
            @Param("totalAmount") BigDecimal totalAmount, @Param("depositRequiredAmount") BigDecimal depositRequiredAmount,
            @Param("shipmentRequiredAmount") BigDecimal shipmentRequiredAmount, @Param("documentJson") String documentJson,
            @Param("agentId") Long agentId);
    int countPayments(@Param("id") long id);
    int countShipments(@Param("orderId") long orderId);
    int countEnabledCustomer(@Param("id") Long id);
    int countEnabledAgent(@Param("id") Long id);
    PiSkuSnapshot selectEnabledSku(@Param("id") Long id);
    PiImageService.StoredImage selectSkuImage(@Param("skuId") long skuId,@Param("imageId") String imageId);
    Long lockOrder(@Param("id") long id);
    int countForOrder(@Param("id") long id);
    int countOrder(@Param("id") Long id, @Param("customerId") Long customerId, @Param("agentId") Long agentId);
    List<PiOrderLine> selectOrderLines(@Param("orderId") long orderId);
    int updateStatus(@Param("id") long id, @Param("from") String from, @Param("status") String status,
            @Param("operatorId") Long operatorId, @Param("released") boolean released);
    int insertPayment(@Param("piId") long piId, @Param("request") PiPaymentRequest request,
            @Param("operatorId") Long operatorId);
    PiPaymentView selectPaymentByRequest(@Param("piId") long piId, @Param("requestId") String requestId);
    PiPaymentView selectPaymentForUpdate(@Param("piId") long piId,@Param("paymentId") long paymentId);
    Long paymentOrigin(@Param("piId") long piId,@Param("paymentId") long paymentId);
    Long currentRevisionId(@Param("piId") long piId);
    int reversePayment(@Param("paymentId") long paymentId,@Param("request") PiPaymentReverseRequest request,@Param("operatorId") Long operatorId);
    int syncSeriesPaymentStatus(@Param("piId") long piId,@Param("amount") BigDecimal amount);
    int blockReleaseAfterCorrection(@Param("id") long id);
    BigDecimal paymentTotal(@Param("piId") long piId);
    int updatePaymentStatus(@Param("id") long id, @Param("status") String status);
    int insertLogistics(@Param("piId") long piId, @Param("request") PiLogisticsRequest request,
            @Param("operatorId") Long operatorId);
    List<PiItemView> selectItems(@Param("piId") long piId);
    List<PiPaymentView> selectPayments(@Param("piId") long piId);
    List<PiLogisticsView> selectLogistics(@Param("piId") long piId);
    Long selectInsertedId();
}
