package com.bike.server.production;

import static com.bike.server.production.ProductionContracts.*;

import com.bike.server.assembly.AssemblyService;
import com.bike.server.assembly.AssemblySubmitRequest;
import com.bike.server.common.*;
import com.bike.server.validation.SnRuleService;
import com.bike.server.vehicle.VehicleMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductionServiceImpl implements ProductionService {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(ProductionServiceImpl.class);
  private final ProductionMapper mapper;
  private final ProductionGuard guard;
  private final AssemblyService assembly;
  private final VehicleMapper vehicles;
  private final SnRuleService snRules;
  private final ObjectMapper json;
  private final ScanFailureService scanFailures;
  private final EanBarcodeService eanBarcodes;

  public ProductionServiceImpl(
      ProductionMapper mapper,
      ProductionGuard guard,
      AssemblyService assembly,
      VehicleMapper vehicles,
      SnRuleService snRules,
      ObjectMapper json,
      ScanFailureService scanFailures,
      EanBarcodeService eanBarcodes) {
    this.mapper = mapper;
    this.guard = guard;
    this.assembly = assembly;
    this.vehicles = vehicles;
    this.snRules = snRules;
    this.json = json;
    this.scanFailures = scanFailures;
    this.eanBarcodes = eanBarcodes;
  }

  @Override
  public List<Map<String, Object>> listSkus() {
    ProductionAccess.read();
    var skus = mapper.selectSkus();
    skus.forEach(
        sku ->
            List.of(
                    "category",
                    "batteryDescription",
                    "packageQuantity",
                    "cartonSizeCm",
                    "grossWeightKg",
                    "netWeightKg",
                    "createdAt",
                    "updatedAt")
                .forEach(field -> sku.putIfAbsent(field, null)));
    return skus;
  }

  @Override
  public List<Map<String, Object>> listSkuNames(long skuId) {
    ProductionAccess.read();
    required(mapper.selectSku(skuId), "SKU不存在");
    var names = mapper.selectSkuNames(skuId);
    names.forEach(
        name -> {
          name.putIfAbsent("ean", null);
          name.putIfAbsent("sn", null);
          String ean = clean(text(name, "ean"));
          name.put("eanBarcodeDataUrl", ean == null ? null : eanBarcodes.dataUrl(ean));
        });
    return names;
  }

  @Override
  @Transactional
  public void saveSkuNames(long skuId, List<SkuNameRequest> names) {
    ProductionAccess.manage();
    guard.lock();
    var sku = required(mapper.selectSku(skuId), "SKU不存在");
    Set<Long> ids = new HashSet<>();
    Set<String> boundNames = new HashSet<>();
    Set<String> eans = new HashSet<>();
    Set<String> sns = new HashSet<>();
    List<SkuNameRequest> normalized = new ArrayList<>();
    for (SkuNameRequest request : names) {
      String name = clean(request.name());
      String ean = clean(request.ean());
      String sn = clean(request.sn());
      if (name == null) fail("INVALID_PARAMETER", "Name不能为空");
      eanBarcodes.validate(ean);
      if (request.id() != null && !ids.add(request.id()))
        fail("INVALID_PARAMETER", "同一Name记录不能重复提交");
      if (!boundNames.add(name.toUpperCase(Locale.ROOT)))
        fail("INVALID_PARAMETER", "同一批次中Name不能重复");
      if (ean != null && !eans.add(ean)) fail("INVALID_PARAMETER", "同一批次中EAN不能重复");
      if (sn != null && !sns.add(sn.toUpperCase(Locale.ROOT)))
        fail("INVALID_PARAMETER", "同一批次中SN不能重复");
      normalized.add(new SkuNameRequest(request.id(), name, ean, sn, request.enabled()));
    }
    try {
      for (SkuNameRequest request : normalized) {
        if (request.id() == null) mapper.insertSkuName(skuId, request);
        else {
          var existing = required(mapper.selectSkuName(request.id()), "Name记录不存在");
          if (number(existing, "skuId") != skuId)
            fail("SKU_NAME_SCOPE_MISMATCH", "Name记录不属于当前SKU");
          if (mapper.updateSkuName(skuId, request) != 1)
            fail("PRODUCTION_NOT_FOUND", "Name记录不存在");
        }
      }
    } catch (DuplicateKeyException error) {
      throw new BusinessException("SKU_NAME_DUPLICATE", "Name、EAN或SN已绑定其他记录");
    }
    audit(text(sku, "skuCode"), "批量维护SKU Name", "本次保存" + normalized.size() + "条");
  }

  @Override
  public Map<String, String> eanBarcode(String ean) {
    ProductionAccess.read();
    String normalized = clean(ean);
    if (normalized == null) fail("BARCODE_INVALID", "EAN不能为空");
    return Map.of("ean", normalized, "dataUrl", eanBarcodes.dataUrl(normalized));
  }

  @Override
  public List<Map<String, Object>> inventory(Long skuId, Long warehouseId) {
    ProductionAccess.read();
    return mapper.selectProductionInventory(skuId, warehouseId);
  }
  @Override public List<Map<String,Object>> inventory(Long skuId, Long warehouseId, Long locationId) {
    ProductionAccess.read();
    return mapper.selectProductionInventoryFiltered(skuId,warehouseId,locationId);
  }

  @Override
  @Transactional
  public void createSku(SkuRequest request, String imageDraftToken) {
    saveSku(null, request);
    if (imageDraftToken == null) return;
    if (!imageDraftToken.matches("[A-Za-z0-9_-]{1,64}")) fail("INVALID_PARAMETER", "图片草稿标识无效");
    var draft = required(mapper.lockSkuImageDraft(imageDraftToken), "图片草稿不存在或已失效");
    if (!CurrentOperator.username().equals(draft.get("createdBy"))) fail("PERMISSION_DENIED", "无权绑定其他账号的图片草稿");
    long imageCount = mapper.countDraftAttachments(imageDraftToken);
    if (imageCount < 1) fail("ATTACHMENT_DRAFT_EMPTY", "图片草稿中没有可绑定的图片");
    var savedSku = required(mapper.selectSkuByCode(request.skuCode().trim()), "SKU保存结果不存在");
    if (mapper.bindDraftAttachments(imageDraftToken, number(savedSku, "id")) != imageCount
        || mapper.deleteSkuImageDraft(imageDraftToken) != 1)
      fail("ATTACHMENT_DRAFT_BIND_FAILED", "SKU已回滚，商品图片绑定失败，请重试");
  }

  @Override
  @Transactional
  public void saveSku(Long id, SkuRequest request) {
    ProductionAccess.manage();
    guard.lock();
    if (vehicles.countEnabledModel(request.modelId()) != 1) fail("MODEL_NOT_FOUND", "车型不存在或已停用");
    required(mapper.selectTemplate(request.templateId()), "标签模板不存在");
    var normalized =
        new SkuRequest(
            request.modelId(),
            request.skuCode().trim(),
            request.productName().trim(),
            clean(request.specification()),
            clean(request.ean()),
            clean(request.upc()),
            request.templateId(),
            request.enabled(),
            clean(request.category()),
            clean(request.batteryDescription()),
            request.packageQuantity(),
            clean(request.cartonSizeCm()),
            request.grossWeightKg(),
            request.netWeightKg());
    if (normalized.skuCode().isEmpty() || normalized.productName().isEmpty())
      fail("INVALID_PARAMETER", "SKU编码和名称不能为空");
    eanBarcodes.validate(normalized.ean());
    validateBarcode(normalized.upc(), 12, "UPC");
    if (normalized.grossWeightKg() != null
        && normalized.netWeightKg() != null
        && normalized.grossWeightKg().compareTo(normalized.netWeightKg()) < 0)
      fail("INVALID_PARAMETER", "毛重不能小于净重");
    try {
      if (id == null) mapper.insertSku(normalized);
      else {
        var existing = required(mapper.selectSku(id), "SKU不存在");
        if (number(existing, "modelId") != request.modelId()
            || !text(existing, "skuCode").equals(normalized.skuCode()))
          fail("SKU_IDENTITY_IMMUTABLE", "已建立SKU的车型和编码不可更改，请新增SKU");
        mapper.updateSku(id, normalized);
      }
    } catch (DuplicateKeyException e) {
      throw new BusinessException("SKU_DUPLICATE", "SKU编码已存在");
    }
    audit(normalized.skuCode(), "维护生产SKU", normalized.productName());
  }

  @Override
  public List<Map<String, Object>> listTemplates() {
    ProductionAccess.read();
    return mapper.selectTemplates().stream().map(template -> {
      Map<String, Object> result = new java.util.LinkedHashMap<>(template);
      result.putIfAbsent("createdAt", null);
      result.putIfAbsent("updatedAt", null);
      return result;
    }).toList();
  }

  @Override
  @Transactional
  public void updateTemplate(long id, TemplateRequest request) {
    ProductionAccess.manage();
    guard.lock();
    required(mapper.selectTemplate(id), "模板不存在");
    mapper.updateTemplate(id, request);
    audit(String.valueOf(id), "发布标签模板", request.name());
  }

  @Override
  @Transactional
  public Map<String, Object> createWorkOrder(WorkOrderRequest request) {
    ProductionAccess.manage();
    guard.lock();
    String batchNo = clean(request.batchNo());
    var existing = mapper.selectWorkOrderByRequestId(request.requestId());
    if (existing != null) {
      if (number(existing, "skuId") != request.skuId()
          || number(existing, "plannedQuantity") != request.plannedQuantity()
          || !Objects.equals(text(existing, "batchNo"), batchNo))
        fail("IDEMPOTENCY_CONFLICT", "同一请求标识不能用于不同生产工单");
      return getWorkOrder(number(existing, "id"), 1);
    }
    var sku = required(mapper.selectSku(request.skuId()), "SKU不存在");
    requireEnabled(sku);
    var normalized =
        new WorkOrderRequest(
            request.requestId(), request.skuId(), batchNo, request.plannedQuantity());
    String workOrderNo =
        "WO-"
            + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            + "-"
            + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    try {
      mapper.insertWorkOrder(
          request.requestId(), workOrderNo, normalized, CurrentOperator.username());
    } catch (DuplicateKeyException error) {
      var duplicate = mapper.selectWorkOrderByRequestId(request.requestId());
      if (duplicate == null) throw new BusinessException("WORK_ORDER_DUPLICATE", "生产工单号冲突，请重试");
      if (number(duplicate, "skuId") != request.skuId()
          || number(duplicate, "plannedQuantity") != request.plannedQuantity()
          || !Objects.equals(text(duplicate, "batchNo"), batchNo))
        fail("IDEMPOTENCY_CONFLICT", "同一请求标识不能用于不同生产工单");
      return getWorkOrder(number(duplicate, "id"), 1);
    }
    var created = required(mapper.selectWorkOrderByRequestId(request.requestId()), "生产工单创建失败");
    audit(workOrderNo, "创建生产工单", text(sku, "skuCode") + ":" + request.plannedQuantity());
    return getWorkOrder(number(created, "id"), 1);
  }

  @Override
  public Map<String, Object> getWorkOrder(long id, long taskPage) {
    ProductionAccess.read();
    long page = Math.min(10_000_000, Math.max(1, taskPage));
    var workOrder = required(mapper.selectWorkOrder(id), "生产工单不存在");
    workOrder.put(
        "tasks",
        new PageResponse<>(
            mapper.selectWorkOrderTasks(id, (page - 1) * 50),
            page,
            50,
            mapper.countWorkOrderTasks(id)));
    return workOrder;
  }

  @Override
  public PageResponse<Map<String, Object>> listWorkOrders(
      String keyword,
      long page,
      Long skuId,
      String status,
      java.time.LocalDate fromDate,
      java.time.LocalDate toDate) {
    ProductionAccess.read();
    String state = clean(status);
    if (state != null && !Set.of("RELEASED", "PAUSED", "COMPLETED").contains(state))
      fail("INVALID_PARAMETER", "工单状态无效");
    if (fromDate != null && toDate != null && fromDate.isAfter(toDate))
      fail("INVALID_PARAMETER", "开始日期不能晚于结束日期");
    long currentPage = Math.min(10_000_000, Math.max(1, page));
    String query = clean(keyword);
    return new PageResponse<>(
        mapper.selectWorkOrders(
            query, (currentPage - 1) * 50, skuId, state, fromDate, toDate),
        currentPage,
        50,
        mapper.countWorkOrders(query, skuId, state, fromDate, toDate));
  }

  @Override
  @Transactional
  public Map<String, Object> scanWorkOrder(long id, WorkOrderScanRequest request) {
    String failureTaskId = clean(request.taskId());
    var scanRequest = new ScanRequest(request.code(), request.expectedType());
    try {
      ProductionAccess.operate();
      guard.lock();
      var workOrder = required(mapper.selectWorkOrder(id), "生产工单不存在");
      if (!"RELEASED".equals(text(workOrder, "status")))
        fail("WORK_ORDER_NOT_RELEASED", "生产工单未处于可生产状态");
      String taskId = failureTaskId;
      if (taskId == null) {
        if (mapper.countActiveWorkOrderTasks(id) >= number(workOrder, "plannedQuantity"))
          fail("WORK_ORDER_QUANTITY_REACHED", "生产工单计划数量已全部占用或完成");
        taskId = UUID.randomUUID().toString();
        mapper.insertWorkOrderTask(
            taskId,
            id,
            number(workOrder, "skuId"),
            text(workOrder, "batchNo"),
            CurrentOperator.username());
        audit(taskId, "生产工单自动创建单车任务", text(workOrder, "workOrderNo"));
      } else {
        var task = required(mapper.selectTask(taskId), "生产任务不存在");
        if (number(task, "workOrderId") != id)
          fail("WORK_ORDER_TASK_MISMATCH", "生产任务不属于当前工单");
      }
      var result = new LinkedHashMap<>(scanLocked(taskId, scanRequest));
      mapper.completeWorkOrderIfPlanned(id);
      result.put("workOrder", required(mapper.selectWorkOrder(id), "生产工单不存在"));
      return result;
    } catch (BusinessException error) {
      scanFailures.record(failureTaskId == null ? "WORK-ORDER-" + id : failureTaskId, scanRequest, error);
      throw error;
    }
  }

  @Override
  @Transactional
  public void pauseWorkOrder(long id, String reason) {
    ProductionAccess.manage();
    guard.lock();
    var workOrder = required(mapper.selectWorkOrder(id), "生产工单不存在");
    if ("PAUSED".equals(text(workOrder, "status"))) return;
    if (mapper.updateWorkOrderStatus(id, "RELEASED", "PAUSED") != 1)
      fail("INVALID_STATUS_TRANSITION", "仅已下达工单可以暂停");
    audit(text(workOrder, "workOrderNo"), "暂停生产工单", reason.trim());
  }

  @Override
  @Transactional
  public void resumeWorkOrder(long id, String reason) {
    ProductionAccess.manage();
    guard.lock();
    var workOrder = required(mapper.selectWorkOrder(id), "生产工单不存在");
    if ("RELEASED".equals(text(workOrder, "status"))) return;
    if (mapper.updateWorkOrderStatus(id, "PAUSED", "RELEASED") != 1)
      fail("INVALID_STATUS_TRANSITION", "仅已暂停工单可以恢复");
    audit(text(workOrder, "workOrderNo"), "恢复生产工单", reason.trim());
  }

  @Override
  @Transactional
  public Map<String, Object> createTask(TaskRequest request) {
    ProductionAccess.operate();
    guard.lock();
    var existing = mapper.selectTask(request.requestId());
    if (existing != null) {
      if (number(existing, "skuId") != request.skuId()
          || !Objects.equals(clean(text(existing, "batchNo")), clean(request.batchNo())))
        fail("IDEMPOTENCY_CONFLICT", "同一请求标识不能用于不同生产任务");
      return getTask(request.requestId());
    }
    var sku = required(mapper.selectSku(request.skuId()), "SKU不存在");
    requireEnabled(sku);
    mapper.insertTask(
        request.requestId(),
        new TaskRequest(request.requestId(), request.skuId(), clean(request.batchNo())),
        CurrentOperator.username());
    audit(request.requestId(), "创建生产任务", text(sku, "skuCode"));
    return getTask(request.requestId());
  }

  @Override
  public Map<String, Object> getTask(String id) {
    ProductionAccess.read();
    var task = required(mapper.selectTask(id), "生产任务不存在");
    task.put("scans", mapper.selectScans(id));
    return task;
  }

  @Override
  public PageResponse<Map<String, Object>> listTasks(String keyword, long page) {
    ProductionAccess.read();
    long p = Math.max(1, page);
    return new PageResponse<>(
        mapper.selectTasks(clean(keyword), (p - 1) * 50), p, 50, mapper.countTasks(clean(keyword)));
  }
  @Override public PageResponse<Map<String,Object>> listTasks(String keyword, long page, Long skuId, String status,
      java.time.LocalDate fromDate, java.time.LocalDate toDate) {
    ProductionAccess.read();
    String state=clean(status);
    if(state!=null && !Set.of("OPEN","COMPLETED","CANCELLED").contains(state)) fail("INVALID_PARAMETER","任务状态无效");
    if(fromDate!=null && toDate!=null && fromDate.isAfter(toDate)) fail("INVALID_PARAMETER","开始日期不能晚于结束日期");
    long p=Math.min(10_000_000,Math.max(1,page));
    return new PageResponse<>(mapper.selectTasksFiltered(clean(keyword),(p-1)*50,skuId,state,fromDate,toDate),p,50,
        mapper.countTasksFiltered(clean(keyword),skuId,state,fromDate,toDate));
  }

  @Override
  @Transactional
  public Map<String, Object> scan(String id, ScanRequest request) {
    try {
      return scanLocked(id, request);
    } catch (BusinessException error) {
      scanFailures.record(id, request, error);
      throw error;
    }
  }

  private Map<String, Object> scanLocked(String id, ScanRequest request) {
    ProductionAccess.operate();
    guard.lock();
    var task = required(mapper.selectTask(id), "生产任务不存在");
    String raw = request.code().trim().toUpperCase(Locale.ROOT);
    if (!"OPEN".equals(task.get("status"))) {
      if ("COMPLETED".equals(task.get("status"))
          && mapper.selectScans(id).stream().anyMatch(s -> raw.equals(s.get("code"))))
        return getTask(id);
      fail("PRODUCTION_TASK_CLOSED", "该任务已结束，请创建下一辆车的任务");
    }
    var part = mapper.selectPart(raw);
    var frame = mapper.selectFrame(raw);
    String type = request.expectedType();
    if (type == null) {
      if (part != null && frame != null) fail("AMBIGUOUS_CODE", "该码同时存在于车架和部件，请选择编码类型");
      type = part != null ? text(part, "codeType") : frame != null ? "FRAME" : null;
      if (type == null) fail("CODE_NOT_FOUND", "编码未登记；新车架请选择车架类型，部件请先登记或导入");
    }
    if (!Set.of("FRAME", "MOTOR", "BATTERY", "DISPLAY").contains(type))
      fail("PART_TYPE_MISMATCH", "此部件类型不属于四码组装");
    ProductionAccess.scan(type);
    String code =
        "FRAME".equals(type) ? snRules.normalizeFrameSn(raw) : snRules.normalizePartSn(raw, type);
    guard.requireCodeAvailable(code, id);
    var scans = mapper.selectScans(id);
    for (var scan : scans) {
      if (code.equals(scan.get("code")) && type.equals(scan.get("codeType"))) return getTask(id);
      if (type.equals(scan.get("codeType"))) fail("SCAN_TYPE_EXISTS", "该类型已经扫描；需要换码时取消任务后重新开始");
      if (code.equals(scan.get("code"))) fail("SCAN_DUPLICATE", "同一编码不能用于不同类型");
    }
    var sku = required(mapper.selectSku(number(task, "skuId")), "SKU不存在");
    requireEnabled(sku);
    if ("FRAME".equals(type)) {
      if (part != null) fail("PART_TYPE_MISMATCH", "该编码已登记为部件，不能作为车架");
      if (frame != null
          && (number(frame, "status") != 0 || number(frame, "modelId") != number(sku, "modelId")))
        fail("FRAME_NOT_AVAILABLE", "车架状态或车型与任务不匹配");
    } else {
      if (part == null) fail("PART_NOT_FOUND", "零部件不存在，请先登记或导入");
      if (!type.equals(part.get("codeType"))) fail("PART_TYPE_MISMATCH", "扫描编码与所选类型不匹配");
      if (!"UNBOUND".equals(part.get("bindStatus")) || !"AVAILABLE".equals(part.get("status")))
        fail("PART_NOT_AVAILABLE", "部件已绑定或不可用");
      if (!"PASSED".equals(part.get("qcStatus"))) fail("PART_QC_FAILED", "部件尚未质量放行");
    }
    mapper.insertScan(id, type, code, CurrentOperator.username());
    mapper.touchTask(id);
    audit(id, "生产扫码", type + ":" + code);
    if (scans.size() == 3) return completeLocked(task);
    return getTask(id);
  }

  @Override
  @Transactional
  public Map<String, Object> complete(String id) {
    ProductionAccess.operate();
    guard.lock();
    var task = required(mapper.selectTask(id), "生产任务不存在");
    if ("COMPLETED".equals(task.get("status"))) return getTask(id);
    if (!"OPEN".equals(task.get("status"))) fail("PRODUCTION_TASK_CLOSED", "任务已取消");
    return completeLocked(task);
  }

  private Map<String, Object> completeLocked(Map<String, Object> task) {
    String id = text(task, "id");
    var scans = mapper.selectScans(id);
    if (scans.size() != 4) fail("FOUR_CODES_REQUIRED", "四码尚未齐全");
    Map<String, String> codes = new HashMap<>();
    for (var scan : scans) {
      String code = text(scan, "code");
      guard.requireCodeAvailable(code, id);
      codes.put(text(scan, "codeType"), code);
    }
    var sku = required(mapper.selectSku(number(task, "skuId")), "SKU不存在");
    requireEnabled(sku);
    String frameSn = codes.get("FRAME");
    var frame = mapper.selectFrame(frameSn);
    if (frame == null) mapper.insertVehicle(frameSn, number(sku, "modelId"));
    else if (number(frame, "modelId") != number(sku, "modelId"))
      fail("ORDER_MODEL_MISMATCH", "车架车型与任务不匹配");
    // 已持有全局生产事务锁，释放预占后调用原组装逻辑，不存在其他请求插入的空隙。
    mapper.deleteTaskReservations(id);
    var vehicle =
        assembly.submitCollected(
            id,
            new AssemblySubmitRequest(
                frameSn, codes.get("MOTOR"), codes.get("BATTERY"), codes.get("DISPLAY")));
    mapper.insertProduct(vehicle.getId(), id, number(task, "skuId"), text(task, "batchNo"));
    mapper.updateTaskStatus(id, "COMPLETED", vehicle.getId());
    var template = required(mapper.selectTemplate(number(sku, "templateId")), "标签模板不存在");
    var snapshot =
        new PrintSnapshot(
            frameSn,
            codes.get("MOTOR"),
            codes.get("BATTERY"),
            codes.get("DISPLAY"),
            vehicle.getVehicleQrCode(),
            text(sku, "skuCode"),
            text(sku, "modelName"),
            text(sku, "productName"),
            text(sku, "specification"),
            text(sku, "ean"),
            text(sku, "upc"),
            text(task, "batchNo"),
            text(template, "name"),
            (int) number(template, "version"),
            (int) number(template, "widthMm"),
            (int) number(template, "heightMm"),
            text(template, "layout"),
            text(template, "heading"));
    mapper.insertPrint(
        "FIRST-" + id,
        vehicle.getId(),
        serialize(snapshot),
        "四码完成首次打印",
        CurrentOperator.username());
    audit(id, "生产完成并创建首打任务", frameSn);
    return getTask(id);
  }

  @Override
  @Transactional
  public void cancel(String id, String reason) {
    ProductionAccess.operate();
    guard.lock();
    var task = required(mapper.selectTask(id), "任务不存在");
    if (!"OPEN".equals(task.get("status"))) fail("PRODUCTION_TASK_CLOSED", "仅未完成任务可取消");
    mapper.deleteTaskReservations(id);
    mapper.updateTaskStatus(id, "CANCELLED", null);
    audit(id, "取消生产任务", reason);
  }

  @Override
  public PageResponse<Map<String, Object>> listIssues(String keyword, long page) {
    ProductionAccess.read();
    long p = Math.max(1, page);
    var rows = mapper.selectIssues(clean(keyword), (p - 1) * 50);
    rows.forEach(r -> r.put("attachments", mapper.selectAttachments(text(r, "id"))));
    return new PageResponse<>(rows, p, 50, mapper.countIssues(clean(keyword)));
  }

  @Override
  @Transactional
  public Map<String, Object> createIssue(IssueRequest request) {
    ProductionAccess.read();
    CurrentOperator.requireAnyPermission("ASSEMBLY_OPERATE", "REWORK_OPERATE");
    guard.lock();
    String raw = request.code().trim();
    String frame = mapper.selectFrameByQr(raw);
    String code = frame == null ? raw.toUpperCase(Locale.ROOT) : frame;
    if (mapper.selectFrame(code) == null
        && mapper.selectPart(code) == null
        && mapper.selectReservation(code) == null) fail("CODE_NOT_FOUND", "车辆或部件编码不存在");
    String id = UUID.randomUUID().toString();
    mapper.insertIssue(id, code, request.reason().trim(), CurrentOperator.username());
    audit(id, "登记异常并隔离", code + ":" + request.reason());
    return mapper.selectIssue(id);
  }

  @Override
  @Transactional
  public void resolveIssue(String id, String reason) {
    ProductionAccess.manage();
    guard.lock();
    required(mapper.selectIssue(id), "异常不存在");
    if (mapper.updateIssueResolved(id, reason, CurrentOperator.username()) != 1)
      fail("INVALID_STATUS_TRANSITION", "异常已处理");
    audit(id, "解除生产隔离", reason);
  }

  @Override
  public PageResponse<Map<String, Object>> listQuarantinedParts(String keyword, long page) {
    ProductionAccess.read();
    long p = Math.max(1, page);
    return new PageResponse<>(mapper.selectQuarantinedParts(clean(keyword), (p - 1) * 50),
        p, 50, mapper.countQuarantinedParts(clean(keyword)));
  }

  @Override
  @Transactional
  public void releasePart(String code, String reason) {
    ProductionAccess.manage();
    guard.lock();
    String normalized = snRules.normalizePartSn(code, null);
    guard.requireCodeAvailable(normalized, null);
    if (mapper.updatePartReleased(normalized) != 1)
      fail("PART_RELEASE_FORBIDDEN", "仅隔离且未绑定、无未处理异常的部件可复检放行");
    audit(normalized, "旧件复检合格放行", reason);
  }

  @Override
  public PageResponse<Map<String, Object>> listPrints(long page) {
    ProductionAccess.read();
    long p = Math.max(1, page);
    return new PageResponse<>(mapper.selectPrints((p - 1) * 50), p, 50, mapper.countPrints());
  }

  @Override
  public Map<String, Object> getPrint(String id) {
    ProductionAccess.read();
    return required(mapper.selectPrint(id), "打印任务不存在");
  }

  @Override
  public List<Map<String, Object>> taskPrints(String taskId) {
    ProductionAccess.read();
    var task = required(mapper.selectTask(taskId), "生产任务不存在");
    if (task.get("vehicleId") == null) return List.of();
    return mapper.selectVehiclePrints(number(task, "vehicleId"));
  }

  @Override
  @Transactional
  public Map<String, Object> claimPrint(String id, String terminal) {
    ProductionAccess.operate();
    guard.lock();
    mapper.updateStalePrints();
    var job =
        id == null ? mapper.selectPendingPrint() : required(mapper.selectPrint(id), "打印任务不存在");
    if (job == null) return null;
    guard.requireVehicleReleased(number(job, "vehicleId"));
    var vehicle = vehicles.selectById(number(job, "vehicleId"));
    if (vehicle == null
        || !Objects.equals(
            vehicle.getVehicleQrCode(), readSnapshot(text(job, "snapshot")).qrCode()))
      fail("QR_VERSION_CHANGED", "标签二维码已失效，禁止打印旧标签");
    if (mapper.updatePrintClaim(text(job, "id"), terminal, CurrentOperator.username()) != 1)
      fail("PRINT_ALREADY_CLAIMED", "打印任务已领取或处理，禁止重复发送");
    audit(text(job, "id"), "领取打印任务", terminal);
    return mapper.selectPrint(text(job, "id"));
  }

  @Override
  @Transactional
  public void reportPrint(String id, PrintResultRequest request) {
    ProductionAccess.operate();
    guard.lock();
    var job = required(mapper.selectPrint(id), "打印任务不存在");
    if (!CurrentOperator.username().equals(job.get("claimedBy")))
      fail("PERMISSION_DENIED", "只能回报当前账号领取的打印任务");
    if (Objects.equals(job.get("status"), request.status())) return;
    if (mapper.updatePrintResult(id, request.status(), request.message()) != 1)
      fail("PRINT_STATUS_CONFLICT", "打印状态已改变，请核对实物");
    audit(id, "打印结果", request.status() + ":" + request.message());
  }

  @Override
  @Transactional
  public Map<String, Object> reprint(String id, String reason) {
    ProductionAccess.operate();
    guard.lock();
    var job = required(mapper.selectPrint(id), "打印任务不存在");
    if (Set.of("PENDING", "PROCESSING").contains(text(job, "status")))
      fail("PRINT_PENDING", "原任务仍待打印，请先处理原任务");
    if (mapper.countActivePrints(number(job, "vehicleId")) > 0)
      fail("PRINT_PENDING", "该车辆已有待处理打印任务，不能重复创建补打");
    guard.requireVehicleReleased(number(job, "vehicleId"));
    var vehicle = vehicles.selectById(number(job, "vehicleId"));
    var previous = readSnapshot(text(job, "snapshot"));
    if (vehicle == null || vehicle.getVehicleQrCode() == null)
      fail("QR_NOT_AVAILABLE", "车辆尚未生成有效总码");
    var codes = new HashMap<String, String>();
    mapper
        .selectCurrentBindings(vehicle.getId())
        .forEach(b -> codes.put(text(b, "codeType"), text(b, "code")));
    if (codes.size() != 3) fail("FOUR_CODES_REQUIRED", "车辆当前部件不齐全");
    // 商品及版式保持原快照，换件和换码后使用当前四码，历史打印记录不变。
    var snapshot =
        new PrintSnapshot(
            vehicle.getFrameSn(),
            codes.get("MOTOR"),
            codes.get("BATTERY"),
            codes.get("DISPLAY"),
            vehicle.getVehicleQrCode(),
            previous.skuCode(),
            previous.modelName(),
            previous.productName(),
            previous.specification(),
            previous.ean(),
            previous.upc(),
            previous.batchNo(),
            previous.templateName(),
            previous.templateVersion(),
            previous.widthMm(),
            previous.heightMm(),
            previous.layout(),
            previous.heading());
    String newId = UUID.randomUUID().toString();
    mapper.insertPrint(
        newId, number(job, "vehicleId"), serialize(snapshot), reason, CurrentOperator.username());
    audit(newId, "标签补打", reason);
    return mapper.selectPrint(newId);
  }

  public PrintSnapshot readSnapshot(String value) {
    try {
      return json.readValue(value, PrintSnapshot.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("标签快照无法解析", e);
    }
  }

  private String serialize(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("标签快照无法保存", e);
    }
  }

  private void validateBarcode(String code, int length, String type) {
    if (code == null) return;
    if (!code.matches("[0-9]{" + length + "}"))
      fail("BARCODE_INVALID", type + "须为" + length + "位数字");
    int sum = 0;
    for (int i = length - 2, weight = 3; i >= 0; i--, weight = 4 - weight)
      sum += (code.charAt(i) - '0') * weight;
    if ((10 - sum % 10) % 10 != code.charAt(length - 1) - '0')
      fail("BARCODE_INVALID", type + "校验位不正确");
  }

  private void requireEnabled(Map<String, Object> sku) {
    Object enabled = sku.get("enabled");
    if (!(Boolean.TRUE.equals(enabled) || enabled instanceof Number n && n.intValue() == 1)
        || vehicles.countEnabledModel(number(sku, "modelId")) != 1)
      fail("SKU_DISABLED", "SKU或车型已停用");
  }

  private void audit(String id, String action, String reason) {
    String summary=reason==null?null:reason.substring(0,Math.min(reason.length(),500));
    if (mapper.insertAudit(id, action, CurrentOperator.username(), summary) != 1)
      fail("AUDIT_LOG_FAILED", "操作记录写入失败");
    LOG.info(
        "生产业务操作 businessId={}, action={}, operator={}", id, action, CurrentOperator.username());
  }

  static Map<String, Object> required(Map<String, Object> row, String message) {
    if (row == null) fail("PRODUCTION_NOT_FOUND", message);
    return row;
  }

  static String text(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value == null ? "" : String.valueOf(value);
  }

  static long number(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value instanceof Number n ? n.longValue() : 0;
  }

  private static String clean(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static void fail(String code, String message) {
    throw new BusinessException(code, message);
  }
}
