package com.bike.server.pi;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record PiWorkflowRequest(
        @Size(max = 500, message = "流程备注不能超过500个字符") String note,
        @Size(max = 128, message = "合同号不能超过128个字符") String contractNo,
        Boolean contractReady,
        Boolean paymentTableReady,
        Boolean productionTableReady,
        @Size(max = 500, message = "生产进度不能超过500个字符") String productionStatus,
        LocalDate expectedCompletionDate,
        @Size(max = 500, message = "材料情况不能超过500个字符") String materialStatus,
        @Size(max = 128, message = "货代名称不能超过128个字符") String forwarderName,
        @DecimalMin(value = "0.00", message = "货代报价不能小于0")
        @Digits(integer = 16, fraction = 2, message = "货代报价最多16位整数和2位小数") BigDecimal quotedAmount,
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "报价币种必须是3位字母代码") String quoteCurrency,
        @Size(max = 128, message = "国内快递公司不能超过128个字符") String domesticCarrier,
        @DecimalMin(value = "0.00", message = "国内快递价格不能小于0")
        @Digits(integer = 16, fraction = 2, message = "国内快递价格最多16位整数和2位小数") BigDecimal domesticPrice,
        @Size(max = 128, message = "国内快递单号不能超过128个字符") String domesticTrackingNo,
        @PastOrPresent(message = "客户收货日期不能晚于今天") LocalDate receivedDate,
        Boolean productionCompleted) {
    public PiWorkflowRequest(String note) {
        this(note, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
