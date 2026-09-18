package com.bike.server.production;

import com.bike.server.common.BusinessException;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ProductionGuard {
  private final ProductionMapper mapper;

  public ProductionGuard(ProductionMapper mapper) {
    this.mapper = mapper;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void lock() {
    mapper.selectMutexForUpdate();
  }

  public void requireCodeAvailable(String code, String taskId) {
    if (mapper.countOpenIssues(code) > 0)
      throw new BusinessException("PRODUCTION_QUARANTINED", "编码存在未处理异常，已隔离：" + code);
    Map<String, Object> reservation = mapper.selectReservation(code);
    if (reservation != null && !java.util.Objects.equals(taskId, reservation.get("taskId")))
      throw new BusinessException("PRODUCTION_RESERVED", "编码已被其他生产任务占用：" + code);
  }

  public void requireVehicleReleased(long vehicleId) {
    if (mapper.countVehicleOpenIssues(vehicleId) > 0)
      throw new BusinessException("PRODUCTION_QUARANTINED", "车辆或当前部件存在未处理异常，不允许流转");
  }

  public void invalidatePendingLabels(long vehicleId) {
    if (mapper.countProcessingPrints(vehicleId) > 0)
      throw new BusinessException("PRINT_IN_PROGRESS", "车辆标签正在打印，请等待结果或超时核对后再换件/换码");
    mapper.updateObsoletePrints(vehicleId, "四码或总码已变更，请核对实物并新建补打任务");
  }

  public void requireCollectedTask(
      String taskId, com.bike.server.assembly.AssemblySubmitRequest request) {
    lock();
    var task = mapper.selectTask(taskId);
    if (task == null || !"OPEN".equals(task.get("status")))
      throw new BusinessException("PRODUCTION_TASK_CLOSED", "生产任务不可完成");
    var codes =
        Map.of(
            "FRAME",
            request.frameSn(),
            "MOTOR",
            request.motorSn(),
            "BATTERY",
            request.batterySn(),
            "DISPLAY",
            request.displaySn());
    var scans = mapper.selectScans(taskId);
    if (scans.size() != 4
        || scans.stream()
            .anyMatch(
                s ->
                    !java.util.Objects.equals(
                        codes.get(String.valueOf(s.get("codeType"))), s.get("code"))))
      throw new BusinessException("FOUR_CODES_REQUIRED", "必须完成各工位四码采集后才能组装");
  }
}
