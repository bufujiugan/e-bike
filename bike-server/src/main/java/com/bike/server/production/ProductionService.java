package com.bike.server.production;

import static com.bike.server.production.ProductionContracts.*;

import com.bike.server.common.PageResponse;
import java.util.List;
import java.util.Map;

public interface ProductionService {
  List<Map<String, Object>> listSkus();

  List<Map<String, Object>> listSkuNames(long skuId);

  void saveSkuNames(long skuId, List<SkuNameRequest> names);

  Map<String, String> eanBarcode(String ean);

  List<Map<String, Object>> inventory(Long skuId, Long warehouseId);
  List<Map<String, Object>> inventory(Long skuId, Long warehouseId, Long locationId);

  void saveSku(Long id, SkuRequest request);

  void createSku(SkuRequest request, String imageDraftToken);

  List<Map<String, Object>> listTemplates();

  void updateTemplate(long id, TemplateRequest request);

  Map<String, Object> createTask(TaskRequest request);

  Map<String, Object> createWorkOrder(WorkOrderRequest request);

  Map<String, Object> getWorkOrder(long id, long taskPage);

  PageResponse<Map<String, Object>> listWorkOrders(
      String keyword,
      long page,
      Long skuId,
      String status,
      java.time.LocalDate fromDate,
      java.time.LocalDate toDate);

  Map<String, Object> scanWorkOrder(long id, WorkOrderScanRequest request);

  void pauseWorkOrder(long id, String reason);

  void resumeWorkOrder(long id, String reason);

  Map<String, Object> getTask(String id);

  PageResponse<Map<String, Object>> listTasks(String keyword, long page);
  PageResponse<Map<String, Object>> listTasks(String keyword, long page, Long skuId, String status, java.time.LocalDate fromDate, java.time.LocalDate toDate);

  Map<String, Object> scan(String id, ScanRequest request);

  Map<String, Object> complete(String id);

  void cancel(String id, String reason);

  PageResponse<Map<String, Object>> listIssues(String keyword, long page);

  Map<String, Object> createIssue(IssueRequest request);

  void resolveIssue(String id, String reason);

  PageResponse<Map<String, Object>> listQuarantinedParts(String keyword, long page);

  void releasePart(String code, String reason);

  PageResponse<Map<String, Object>> listPrints(long page);

  Map<String, Object> getPrint(String id);
  List<Map<String, Object>> taskPrints(String taskId);

  Map<String, Object> claimPrint(String id, String terminal);

  void reportPrint(String id, PrintResultRequest request);

  Map<String, Object> reprint(String id, String reason);
}
