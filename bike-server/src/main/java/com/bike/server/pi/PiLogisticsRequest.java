package com.bike.server.pi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PiLogisticsRequest(@NotBlank(message="物流批次不能为空") @Size(max=64,message="物流批次长度不能超过64") String batchNo,
                                 @Size(max=128,message="承运商长度不能超过128") String carrier,
                                 @Size(max=128,message="运单号长度不能超过128") String trackingNo,
                                 @Size(max=32,message="物流状态长度不能超过32") String status,
                                 @Size(max=500,message="备注长度不能超过500") String remark,
                                 @jakarta.validation.constraints.Positive Long stockOutBatchId,
                                 @Size(max=64) String requestId,
                                 @jakarta.validation.constraints.Positive Integer expectedRevision) {
    public PiLogisticsRequest(String batchNo,String carrier,String trackingNo,String status,String remark) {
        this(batchNo,carrier,trackingNo,status,remark,null,null,null);
    }
}
