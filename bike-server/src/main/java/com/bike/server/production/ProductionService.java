package com.bike.server.production;

import static com.bike.server.production.ProductionContracts.*;

import com.bike.server.common.PageResponse;
import java.util.List;
import java.util.Map;

public interface ProductionService {
  List<Map<String, Object>> listSkus();

  List<Map<String, Object>> inventory(Long skuId, Long warehouseId);

  void saveSku(Long id, SkuRequest request);

  List<Map<String, Object>> listTemplates();

  void updateTemplate(long id, TemplateRequest request);

  Map<String, Object> createTask(TaskRequest request);

  Map<String, Object> getTask(String id);

  PageResponse<Map<String, Object>> listTasks(String keyword, long page);

  Map<String, Object> scan(String id, ScanRequest request);

  Map<String, Object> complete(String id);

  void cancel(String id, String reason);

  PageResponse<Map<String, Object>> listIssues(String keyword, long page);

  Map<String, Object> createIssue(IssueRequest request);

  void resolveIssue(String id, String reason);

  List<Map<String, Object>> listQuarantinedParts();

  void releasePart(String code, String reason);

  PageResponse<Map<String, Object>> listPrints(long page);

  Map<String, Object> getPrint(String id);

  Map<String, Object> claimPrint(String id, String terminal);

  void reportPrint(String id, PrintResultRequest request);

  Map<String, Object> reprint(String id, String reason);
}
