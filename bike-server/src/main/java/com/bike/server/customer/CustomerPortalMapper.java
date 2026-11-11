package com.bike.server.customer;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import com.bike.server.customer.CustomerPortalService.*;

@Mapper
public interface CustomerPortalMapper {
    List<Company> selectCompanies(@Param("userId") long userId);
    Company selectCompany(@Param("customerId") long customerId);
    List<Model> selectModels();
    List<Order> selectOrders(@Param("customerId") long customerId, @Param("offset") long offset);
    long countOrders(@Param("customerId") long customerId);
    Order selectOrder(@Param("orderId") long orderId);
    Submission selectSubmission(@Param("userId") long userId, @Param("requestId") String requestId);
    int insertSubmission(@Param("orderId") long orderId, @Param("userId") long userId, @Param("customerId") long customerId, @Param("request") OrderRequest request, @Param("remark") String remark);
    int insertCompany(@Param("code") String code, @Param("name") String name);
    Long selectCompanyId(@Param("code") String code);
    int insertCustomerRole();
    int insertUser(@Param("request") AccountRequest request, @Param("passwordHash") String passwordHash);
    int insertUserRole(@Param("userId") long userId);
    int insertCustomerBinding(@Param("userId") long userId, @Param("customerId") long customerId, @Param("operatorId") Long operatorId);
    List<Order> selectUnreadOrders(@Param("userId") long userId, @Param("scopeUserId") Long scopeUserId);
    int countSubmission(@Param("orderId") long orderId);
    int insertAcknowledgement(@Param("orderId") long orderId, @Param("userId") long userId);
}

