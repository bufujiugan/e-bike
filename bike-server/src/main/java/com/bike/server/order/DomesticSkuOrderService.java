package com.bike.server.order;

import com.bike.server.lock.InventoryLockView;
import java.util.List;

public interface DomesticSkuOrderService {
    SalesOrderView create(DomesticSkuOrderRequest request);
    SalesOrderView createForPi(DomesticSkuOrderRequest request, String salesType);
    boolean isCreationEnabled();
    List<DomesticSkuOption> skuOptions(String keyword);
    List<InventoryLockView> sources(long id, String keyword);
}
