package com.bike.server.pi;

import java.util.Collection;
import java.util.Map;

/** 供已经完成订单、PI或仓库记录权限校验的业务服务读取购销合同。 */
public interface PiPurchaseContractService {
    PiPurchaseContract findForOrder(long orderId);
    Map<Long, PiPurchaseContract> findForPis(Collection<Long> piIds);
    Map<String, PiPurchaseContract> findForStockRecords(Collection<String> stockNos);
}
