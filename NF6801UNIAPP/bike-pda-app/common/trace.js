export const vehicleFields = [
  ["vehicleQrCode", "整车聚合码"],
  ["frameSn", "车架 SN"],
  ["modelName", "车型"],
  ["skuCode", "SKU"],
  ["batchNo", "批次"],
  ["statusName", "状态"],
  ["motorSn", "电机 SN"],
  ["batterySn", "电池 SN"],
  ["displaySn", "仪表 SN"],
  ["assembleTime", "组装时间"],
  ["assembleOperatorName", "组装人员"],
  ["warehouseName", "仓库"],
  ["locationName", "库位"],
  ["customerName", "客户"],
  ["currentOrderNo", "当前订单"],
];
export const traceGroups = [
  [
    "bindingHistory",
    "绑定历史",
    [
      ["partType", "部件类型"],
      ["partSn", "部件 SN"],
      ["action", "动作"],
      ["operationTime", "时间"],
      ["operatorName", "人员"],
      ["reason", "原因"],
    ],
  ],
  [
    "events",
    "操作事件",
    [
      ["operationTime", "时间"],
      ["action", "动作"],
      ["operatorName", "人员"],
      ["partType", "部件类型"],
      ["partSn", "部件 SN"],
      ["reason", "原因"],
    ],
  ],
  [
    "stockInRecords",
    "入库记录",
    [
      ["stockInNo", "入库单"],
      ["warehouseName", "仓库"],
      ["locationName", "库位"],
      ["operationTime", "时间"],
      ["status", "状态"],
      ["cancelReason", "撤销原因"],
    ],
  ],
  [
    "stockOutRecords",
    "出库记录",
    [
      ["stockOutNo", "出库单"],
      ["piNo", "PI"],
      ["executionRevision", "执行修订"],
      ["dispatchBatchNo", "发运批次"],
      ["dispatchSourceType", "来源"],
      ["logisticsCarrier", "承运商"],
      ["logisticsTrackingNo", "运单号"],
      ["logisticsStatus", "物流状态"],
      ["customerName", "客户"],
      ["orderNo", "订单"],
      ["operationTime", "时间"],
      ["status", "状态"],
      ["cancelReason", "撤销原因"],
    ],
  ],
  [
    "reworkRecords",
    "返工记录",
    [
      ["reworkNo", "返工单"],
      ["partType", "部件类型"],
      ["oldPartSn", "旧件 SN"],
      ["newPartSn", "新件 SN"],
      ["operationTime", "时间"],
      ["operatorName", "人员"],
      ["reason", "原因"],
      ["status", "状态"],
    ],
  ],
];
export function fieldLines(record, fields) {
  return fields.map(
    ([key, label]) =>
      `${label}：${record[key] == null || record[key] === "" ? "—" : record[key]}`,
  );
}
