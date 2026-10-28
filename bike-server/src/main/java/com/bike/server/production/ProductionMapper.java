package com.bike.server.production;

import static com.bike.server.production.ProductionContracts.*;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProductionMapper {
  int selectMutexForUpdate();

  int touchTask(String id);

  List<Map<String, Object>> selectProductionInventory(
      @Param("skuId") Long skuId, @Param("warehouseId") Long warehouseId);
  List<Map<String, Object>> selectProductionInventoryFiltered(@Param("skuId") Long skuId, @Param("warehouseId") Long warehouseId, @Param("locationId") Long locationId);

  List<Map<String, Object>> selectSkus();

  Map<String, Object> selectSku(long id);

  Map<String, Object> selectSkuByCode(String skuCode);

  List<Map<String, Object>> selectSkuNames(long skuId);

  Map<String, Object> selectSkuName(long id);

  int insertSku(@Param("r") SkuRequest request);

  int updateSku(@Param("id") long id, @Param("r") SkuRequest request);

  int insertSkuName(@Param("skuId") long skuId, @Param("r") SkuNameRequest request);

  int updateSkuName(@Param("skuId") long skuId, @Param("r") SkuNameRequest request);

  List<Map<String, Object>> selectTemplates();

  Map<String, Object> selectTemplate(long id);

  int updateTemplate(@Param("id") long id, @Param("r") TemplateRequest request);

  int insertTask(
      @Param("id") String id, @Param("r") TaskRequest request, @Param("operator") String operator);

  int insertWorkOrder(
      @Param("requestId") String requestId,
      @Param("workOrderNo") String workOrderNo,
      @Param("r") WorkOrderRequest request,
      @Param("operator") String operator);

  Map<String, Object> selectWorkOrderByRequestId(String requestId);

  Map<String, Object> selectWorkOrder(long id);

  List<Map<String, Object>> selectWorkOrders(
      @Param("keyword") String keyword,
      @Param("offset") long offset,
      @Param("skuId") Long skuId,
      @Param("status") String status,
      @Param("fromDate") java.time.LocalDate fromDate,
      @Param("toDate") java.time.LocalDate toDate);

  int countWorkOrders(
      @Param("keyword") String keyword,
      @Param("skuId") Long skuId,
      @Param("status") String status,
      @Param("fromDate") java.time.LocalDate fromDate,
      @Param("toDate") java.time.LocalDate toDate);

  List<Map<String, Object>> selectWorkOrderTasks(
      @Param("workOrderId") long workOrderId, @Param("offset") long offset);

  int countWorkOrderTasks(long workOrderId);

  int countActiveWorkOrderTasks(long workOrderId);

  int insertWorkOrderTask(
      @Param("id") String id,
      @Param("workOrderId") long workOrderId,
      @Param("skuId") long skuId,
      @Param("batchNo") String batchNo,
      @Param("operator") String operator);

  int updateWorkOrderStatus(
      @Param("id") long id, @Param("fromStatus") String fromStatus, @Param("status") String status);

  int completeWorkOrderIfPlanned(long id);

  Map<String, Object> selectTask(String id);

  List<Map<String, Object>> selectTasks(
      @Param("keyword") String keyword, @Param("offset") long offset);

  int countTasks(String keyword);
  List<Map<String,Object>> selectTasksFiltered(@Param("keyword") String keyword, @Param("offset") long offset,
      @Param("skuId") Long skuId, @Param("status") String status, @Param("fromDate") java.time.LocalDate fromDate, @Param("toDate") java.time.LocalDate toDate);
  int countTasksFiltered(@Param("keyword") String keyword, @Param("skuId") Long skuId, @Param("status") String status,
      @Param("fromDate") java.time.LocalDate fromDate, @Param("toDate") java.time.LocalDate toDate);

  List<Map<String, Object>> selectScans(String taskId);

  Map<String, Object> selectReservation(String code);

  int insertScan(
      @Param("taskId") String taskId,
      @Param("type") String type,
      @Param("code") String code,
      @Param("operator") String operator);

  int deleteTaskReservations(String taskId);

  int updateTaskStatus(
      @Param("id") String id, @Param("status") String status, @Param("vehicleId") Long vehicleId);

  Map<String, Object> selectPart(String code);

  Map<String, Object> selectFrame(String code);
  String selectFrameByQr(String code);

  int insertVehicle(@Param("frameSn") String frameSn, @Param("modelId") long modelId);

  int insertProduct(
      @Param("vehicleId") long vehicleId,
      @Param("taskId") String taskId,
      @Param("skuId") long skuId,
      @Param("batchNo") String batchNo);

  Map<String, Object> selectProduct(long vehicleId);

  int countOpenIssues(String code);

  List<Map<String, Object>> selectQuarantinedParts(@Param("keyword") String keyword, @Param("offset") long offset);
  int countQuarantinedParts(@Param("keyword") String keyword);

  int updatePartReleased(String code);

  List<Map<String, Object>> selectCurrentBindings(long vehicleId);

  int countVehicleOpenIssues(long vehicleId);

  int insertIssue(
      @Param("id") String id,
      @Param("code") String code,
      @Param("reason") String reason,
      @Param("operator") String operator);

  Map<String, Object> selectIssue(String id);

  List<Map<String, Object>> selectIssues(
      @Param("keyword") String keyword, @Param("offset") long offset);

  int countIssues(String keyword);

  int updateIssueResolved(
      @Param("id") String id, @Param("reason") String reason, @Param("operator") String operator);

  int insertAttachment(
      @Param("id") String id,
      @Param("issueId") String issueId,
      @Param("objectKey") String objectKey,
      @Param("contentType") String contentType,
      @Param("size") long size,
      @Param("name") String name, @Param("sha256") String sha256, @Param("requestId") String requestId,
      @Param("operator") String operator);

  Map<String, Object> selectAttachment(String id);

  List<Map<String, Object>> selectAttachments(String issueId);

  List<Map<String, Object>> selectSkuAttachments(long skuId);

  int insertSkuAttachment(
      @Param("id") String id,
      @Param("skuId") long skuId,
      @Param("objectKey") String key,
      @Param("contentType") String type,
      @Param("size") long size,
      @Param("name") String name, @Param("sha256") String sha256, @Param("requestId") String requestId,
      @Param("operator") String operator);

  int insertSkuImageDraft(@Param("draftToken") String draftToken, @Param("operator") String operator);
  Map<String,Object> lockSkuImageDraft(String draftToken);
  int touchSkuImageDraft(String draftToken);
  Map<String,Object> selectDraftAttachmentRequest(@Param("draftToken") String draftToken, @Param("requestId") String requestId);
  long countDraftAttachments(String draftToken);
  int insertDraftAttachment(
      @Param("id") String id,
      @Param("draftToken") String draftToken,
      @Param("objectKey") String key,
      @Param("contentType") String type,
      @Param("size") long size,
      @Param("name") String name, @Param("sha256") String sha256, @Param("requestId") String requestId,
      @Param("operator") String operator);
  List<Map<String,Object>> selectDraftAttachments(String draftToken);
  int bindDraftAttachments(@Param("draftToken") String draftToken, @Param("skuId") long skuId);
  int deleteDraftAttachments(String draftToken);
  int deleteSkuImageDraft(String draftToken);
  List<String> selectStaleSkuImageDrafts();

  String lockAttachmentIssue(String id);
  Long lockAttachmentSku(long id);
  Map<String,Object> selectAttachmentRequest(@Param("issueId") String issueId, @Param("skuId") Long skuId, @Param("requestId") String requestId);
  long countObjectAttachments(@Param("issueId") String issueId, @Param("skuId") Long skuId);

  int insertPrint(
      @Param("id") String id,
      @Param("vehicleId") long vehicleId,
      @Param("snapshot") String snapshot,
      @Param("reason") String reason,
      @Param("operator") String operator);

  Map<String, Object> selectPrint(String id);
  List<Map<String, Object>> selectVehiclePrints(@Param("vehicleId") long vehicleId);

  List<Map<String, Object>> selectPrints(@Param("offset") long offset);

  int countPrints();

  Map<String, Object> selectPendingPrint();

  int updatePrintClaim(
      @Param("id") String id,
      @Param("terminal") String terminal,
      @Param("operator") String operator);

  int updatePrintResult(
      @Param("id") String id, @Param("status") String status, @Param("message") String message);

  int updateStalePrints();

  int countProcessingPrints(long vehicleId);

  int countActivePrints(long vehicleId);

  int updateObsoletePrints(@Param("vehicleId") long vehicleId, @Param("reason") String reason);

  int insertAudit(
      @Param("businessId") String businessId,
      @Param("action") String action,
      @Param("operator") String operator,
      @Param("reason") String reason);
}
