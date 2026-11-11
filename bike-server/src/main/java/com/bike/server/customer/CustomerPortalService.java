package com.bike.server.customer;

import com.bike.server.common.PageResponse;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface CustomerPortalService {
    Company getCompany();
    List<Model> listModels();
    PageResponse<Order> listOrders(long page);
    Order submitOrder(OrderRequest request);
    Account createAccount(AccountRequest request);
    List<Order> listUnreadOrders();
    void acknowledgeOrder(long orderId);

    record Company(long id, String name) {}
    record Model(long id, String name, long availableQty) {}
    record Order(long id, String orderNo, long customerId, String customerName, long modelId,
                 String modelName, int orderQty, int shippedQty, String status,
                 LocalDate plannedShipDate, String remark, LocalDateTime createdAt) {}
    record OrderRequest(@NotBlank @Pattern(regexp="[a-zA-Z0-9-]{16,64}") String requestId,
                        @Positive long modelId, @Min(1) @Max(100000) int quantity,
                        @FutureOrPresent LocalDate plannedShipDate, @Size(max=500) String remark) {}
    record AccountRequest(@NotBlank @Pattern(regexp="[a-zA-Z0-9_.-]{3,64}") String username,
                          @NotBlank @Size(min=8,max=72) String password,
                          @NotBlank @Size(max=100) String displayName, @Positive Long customerId,
                          @Size(max=150) String companyName) {}
    record Account(long userId, String username, long customerId, String companyName) {}
    record Submission(long orderId, long customerId, long modelId, int quantity, LocalDate plannedShipDate, String remark) {}
}

