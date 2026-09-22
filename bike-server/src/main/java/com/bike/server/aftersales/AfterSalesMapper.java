package com.bike.server.aftersales;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AfterSalesMapper {
    List<AfterSalesCaseView> selectFilteredPage(@Param("filter") AfterSalesCaseFilter filter, @Param("agentId") Long agentId, @Param("salesUserId") Long salesUserId, @Param("monthStart") java.time.LocalDate monthStart, @Param("offset") long offset, @Param("limit") long limit);
    long countFiltered(@Param("filter") AfterSalesCaseFilter filter, @Param("agentId") Long agentId, @Param("salesUserId") Long salesUserId, @Param("monthStart") java.time.LocalDate monthStart);
    AfterSalesDashboardView selectDashboard(@Param("filter") AfterSalesCaseFilter filter, @Param("agentId") Long agentId, @Param("salesUserId") Long salesUserId, @Param("monthStart") java.time.LocalDate monthStart);
    AfterSalesCaseView selectById(@Param("id") long id);
    AfterSalesCaseView selectForUpdate(@Param("id") long id);
    AfterSalesVehicleReference selectVehicleReference(@Param("id") long id);
    int countEnabledCustomer(@Param("id") long id);
    int countEnabledAgent(@Param("id") long id);
    int countEnabledModel(@Param("id") long id);
    int countAccessibleModel(@Param("id") long id, @Param("agentId") long agentId);
    List<AfterSalesReferenceOption> selectCustomers();
    List<AfterSalesReferenceOption> selectAgents();
    List<AfterSalesReferenceOption> selectModels(@Param("agentId") Long agentId);
    int insertCase(@Param("caseNo") String caseNo, @Param("request") AfterSalesCaseCreateRequest request, @Param("agentId") Long agentId, @Param("createdBy") Long createdBy);
    int updateCase(@Param("id") long id, @Param("request") AfterSalesCaseCreateRequest request, @Param("updatedBy") Long updatedBy);
    int updateStatus(@Param("id") long id, @Param("fromStatus") String fromStatus, @Param("status") String status, @Param("reason") String reason, @Param("operatorId") Long operatorId);
    int saveSolution(@Param("id") long id, @Param("request") AfterSalesSolutionRequest request, @Param("operatorId") Long operatorId);
    int insertMessage(@Param("caseId") long caseId, @Param("authorId") Long authorId, @Param("visibility") String visibility, @Param("body") String body);
    List<AfterSalesMessageView> selectMessages(@Param("caseId") long caseId, @Param("includeInternal") boolean includeInternal);
    AfterSalesMessageView selectMessageById(@Param("id") long id);
    int insertShipment(@Param("caseId") long caseId, @Param("request") AfterSalesShipmentRequest request, @Param("createdBy") Long createdBy);
    List<AfterSalesShipmentView> selectShipments(@Param("caseId") long caseId);
    AfterSalesShipmentView selectShipmentById(@Param("id") long id);
    int dispatchShipment(@Param("caseId") long caseId, @Param("id") long id, @Param("request") AfterSalesDispatchRequest request);
    long countPendingShipments(@Param("caseId") long caseId);
    int insertRequiredReturn(@Param("shipmentId") long shipmentId);
    List<AfterSalesReturnView> selectReturns(@Param("caseId") long caseId);
    AfterSalesReturnView selectReturn(@Param("shipmentId") long shipmentId);
    int updateReturn(@Param("caseId") long caseId,@Param("shipmentId") long shipmentId,@Param("status") String status,@Param("request") AfterSalesReturnRequest request,@Param("operatorId") Long operatorId);
    int markReturnReceived(@Param("caseId") long caseId,@Param("shipmentId") long shipmentId);
    long countPendingReturns(@Param("caseId") long caseId);
    int touchCase(@Param("id") long id, @Param("operatorId") Long operatorId);
    int insertAudit(@Param("caseId") long caseId, @Param("action") String action, @Param("operatorId") Long operatorId,
                    @Param("operatorName") String operatorName, @Param("beforeData") String beforeData,
                    @Param("afterData") String afterData, @Param("clientIp") String clientIp);
    List<AfterSalesHistoryView> selectHistory(@Param("caseId") long caseId, @Param("includeInternal") boolean includeInternal);
    List<AfterSalesAttachmentView> selectAttachments(@Param("caseId") long caseId, @Param("includeInternal") boolean includeInternal);
    AfterSalesAttachmentView selectAttachmentView(@Param("id") long id);
    AfterSalesStoredAttachment selectStoredAttachment(@Param("id") long id);
    int insertAttachment(@Param("caseId") long caseId, @Param("messageId") Long messageId, @Param("key") String key,
        @Param("fileName") String fileName, @Param("contentType") String contentType, @Param("fileSize") long fileSize,
        @Param("sha256") String sha256, @Param("visibility") String visibility, @Param("category") String category,
        @Param("uploadedBy") Long uploadedBy);
    Long selectInsertedId();
    int countSalesAgent(@Param("userId") long userId, @Param("agentId") long agentId);
    List<AfterSalesCompanyView> selectCompanies(@Param("agentId") Long agentId, @Param("salesUserId") Long salesUserId);
    AfterSalesCompanyView selectCompany(@Param("agentId") long agentId);
    Long lockAgent(@Param("agentId") long agentId);
    int upsertCompany(@Param("agentId") long agentId, @Param("contact") AfterSalesContact contact, @Param("operatorId") long operatorId);
    int insertContactSnapshot(@Param("caseId") long caseId, @Param("contact") AfterSalesContact contact, @Param("endDate") java.time.LocalDate endDate);
    int updateContactSnapshot(@Param("caseId") long caseId, @Param("contact") AfterSalesContact contact, @Param("endDate") java.time.LocalDate endDate);
    int assignCase(@Param("caseId") long caseId, @Param("assigneeId") Long assigneeId, @Param("operatorId") long operatorId);
    int snapshotAssignee(@Param("caseId") long caseId, @Param("name") String name);
    List<AfterSalesReferenceOption> selectStaffOptions(@Param("assignees") boolean assignees);
    int countAssigneeScope(@Param("userId") long userId, @Param("agentId") Long agentId);
    List<AfterSalesReferenceOption> selectSalespeople(@Param("agentId") long agentId);
    List<AfterSalesMemberView> selectCompanyMembers(@Param("agentId") long agentId);
    List<AfterSalesHistoryView> selectCompanyHistory(@Param("agentId") long agentId);
    int disableSalespeople(@Param("agentId") long agentId, @Param("operatorId") long operatorId);
    int upsertSalesperson(@Param("agentId") long agentId, @Param("userId") long userId, @Param("operatorId") long operatorId);
    int insertCompanyAudit(@Param("agentId") long agentId, @Param("action") String action, @Param("operatorId") long operatorId, @Param("operatorName") String operatorName, @Param("beforeData") String beforeData, @Param("afterData") String afterData, @Param("clientIp") String clientIp);
}
