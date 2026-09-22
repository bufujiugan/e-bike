package com.bike.server.pi;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;

public class PiItemView {
    private String unit;
    private String imageAttachmentId;
    private String imageSha256;
    public String getUnit(){return unit;}
    public void setUnit(String value){unit=value;}
    public String getImageAttachmentId(){return imageAttachmentId;}
    public void setImageAttachmentId(String value){imageAttachmentId=value;}
    public String getImageSha256(){return imageSha256;}
    public void setImageSha256(String value){imageSha256=value;}
    private Long id;
    private Long skuId;
    private Long productModelId;
    private String skuCode;
    private String productName;
    private String specification;
    private String modelName;
    private int quantity;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal unitPrice;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal discountAmount;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal taxAmount;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal lineTotal;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }

    public Long getSkuId() { return skuId; }
    public void setSkuId(Long value) { skuId = value; }

    public Long getProductModelId() { return productModelId; }
    public void setProductModelId(Long value) { productModelId = value; }

    public String getSkuCode() { return skuCode; }
    public void setSkuCode(String value) { skuCode = value; }

    public String getProductName() { return productName; }
    public void setProductName(String value) { productName = value; }

    public String getSpecification() { return specification; }
    public void setSpecification(String value) { specification = value; }

    public String getModelName() { return modelName; }
    public void setModelName(String value) { modelName = value; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int value) { quantity = value; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal value) { unitPrice = value; }

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal value) { discountAmount = value; }

    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal value) { taxAmount = value; }

    public BigDecimal getLineTotal() { return lineTotal; }
    public void setLineTotal(BigDecimal value) { lineTotal = value; }
}

