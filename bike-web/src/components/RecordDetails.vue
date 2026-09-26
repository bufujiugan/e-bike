<script setup lang="ts">
import { computed } from 'vue'
import { reservationLabel, type ReservationStatus } from '../pi-api'
const props = defineProps<{ value: unknown }>()
const labels: Record<string, string> = {
  vehicleQrCode: '聚合码', currentVehicleQrCode: '当前关联车辆聚合码', qrCode: '聚合码', frameSn: '车架码', currentFrameSn: '当前关联车架码',
  motorSn: '电机码', batterySn: '电池码', displaySn: '仪表码', partSn: '部件码', partType: '部件类型',
  modelName: '车型', productName: '商品名称', skuCode: '商品规格（SKU）', batchNo: '批次', specification: '规格',
  id: '记录编号', vehicleId: '车辆记录编号', modelId: '车型记录编号', skuId: '商品记录编号', currentVehicleId: '关联车辆记录编号',
  status: '状态', statusName: '状态说明', enabled: '启用', qcStatus: '质检状态', bindStatus: '绑定状态',
  vehicleQrToken: '聚合码标识', qrVersion: '聚合码版本', qrGeneratedAt: '聚合码生成时间',
  assembleOperatorId: '组装人员编号', assembleOperatorName: '组装人员', assembleTime: '组装时间',
  warehouseName: '仓库', currentWarehouseId: '当前仓库编号', warehouseId: '仓库编号', locationName: '库位',
  locationId: '库位编号', currentLocationId: '当前库位编号', stockInTime: '入库时间', stockOutTime: '出库时间',
  currentCustomerId: '当前客户编号', customerId: '客户编号', customerName: '客户', agentId: '代理商编号', agentName: '代理商',
  currentOrderNo: '当前订单号', orderNo: '订单号', orderId: '订单记录编号', stockOutNo: '出库单号', stockNo: '库存单号',
  reservationStatus: '占用状态', reservationHoldMode: '占用保留方式', reservationHeldAt: '占用保留时间', reservationExpiresAt: '占用截止时间',
  orderQty: '订单数量', orderedQty: '订购数量', shippedQty: '已出库数量', plannedShipDate: '计划发货日期',
  createdAt: '创建时间', updatedAt: '修改时间', inboundTime: '来料时间', operationTime: '操作时间',
  supplierId: '供应商编号', supplierName: '供应商', remark: '备注', reason: '原因', cancelReason: '取消原因',
  createdBy: '创建人', updatedBy: '修改人', operatorName: '操作人', operatorId: '操作人员编号',
  items: '商品明细', vehicles: '车辆明细', shippedVehicles: '已出库车辆', reservation: '库存占用',
  sourceLockId: '来源锁定编号', sourceLockNo: '来源锁定单号', lockNo: '锁定单号', requestId: '业务请求编号',
  reservedQty: '占用数量', remainingQty: '剩余数量', availableQty: '可用数量', effectiveQty: '有效数量',
  expiresAt: '占用截止时间', expireTime: '到期时间', holdMode: '保留方式', holdReason: '保留原因',
  holdAt: '保留时间', paidHoldAt: '收款保留时间', paidHoldReason: '收款保留原因', state: '业务状态',
  piId: 'PI 记录编号', piNo: 'PI 编号', qty: '数量', quantity: '数量', unitPrice: '单价', amount: '金额', currency: '币种',
  type: '类型', productModelId: '车型记录编号', releasedQty: '释放数量', convertedQty: '已转换数量',
  statusLabel: '状态说明', holdUntil: '占用截止', origin: '来源', code: '编码', name: '名称',
}
const statusLabels: Record<string, string> = {
  OPEN: '作业中', COMPLETED: '已完成', CANCELLED: '已取消', DRAFT: '草稿', CONFIRMED: '已确认',
  SHIPPED: '已出库', PARTIAL_SHIPPED: '部分出库', CLOSED: '已关闭', ENABLED: '启用', DISABLED: '停用',
  AVAILABLE: '可用', IN_USE: '使用中', SCRAPPED: '已报废', QUARANTINED: '已隔离',
  PASSED: '合格', FAILED: '不合格', PENDING: '待处理', BOUND: '已绑定', UNBOUND: '未绑定',
  MOTOR: '电机', BATTERY: '电池', DISPLAY: '仪表', FRAME: '车架', IN: '入库', OUT: '出库',
  PAID: '收款达标保留', TIMED: '限时占用', RELEASED: '已释放', FULFILLED: '已履约',
}
const priorities = ['vehicleQrCode', 'currentVehicleQrCode', 'qrCode', 'orderNo', 'partSn', 'frameSn', 'motorSn', 'batterySn', 'displaySn', 'statusName']
function order(keys: string[]) { return [...keys].sort((a, b) => (priorities.includes(a) ? priorities.indexOf(a) : 100) - (priorities.includes(b) ? priorities.indexOf(b) : 100)) }
function record(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value) }
function nested(value: unknown) { return Array.isArray(value) || record(value) }
function label(key: string) { return labels[key] ?? key }
function display(value: unknown, key = '') {
  if (value === null || value === undefined || value === '') return '—'
  if (key === 'reservationStatus') return reservationLabel(value as ReservationStatus)
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (['status', 'qcStatus', 'bindStatus', 'partType', 'holdMode', 'reservationHoldMode', 'type'].includes(key)) return statusLabels[String(value)] ?? String(value)
  return String(value).replace(/^(\d{4}-\d\d-\d\d)T/, '$1 ')
}
const entries = computed(() => record(props.value) ? order(Object.keys(props.value)).map(key => ({ key, value: (props.value as Record<string, unknown>)[key] })) : [])
const arrayRows = computed(() => Array.isArray(props.value) ? props.value : [])
const arrayColumns = computed(() => order([...new Set(arrayRows.value.filter(record).flatMap(row => Object.keys(row)))]))
const tableArray = computed(() => arrayRows.value.length > 0 && arrayRows.value.every(record))
</script>

<template>
  <div class="record-details">
    <template v-if="Array.isArray(value)">
      <el-table v-if="tableArray" :data="arrayRows" max-height="320" stripe>
        <el-table-column v-for="key in arrayColumns" :key="key" :label="label(key)" :min-width="/QrCode|qrCode/.test(key) ? 240 : 140">
          <template #default="{ row }"><RecordDetails v-if="nested(row[key])" :value="row[key]" /><span v-else class="record-value">{{ display(row[key], key) }}</span></template>
        </el-table-column>
      </el-table>
      <p v-else-if="!arrayRows.length" class="record-empty">暂无明细</p>
      <ul v-else class="record-list"><li v-for="(item, index) in arrayRows" :key="index"><RecordDetails v-if="nested(item)" :value="item" /><span v-else>{{ display(item) }}</span></li></ul>
    </template>
    <dl v-else-if="record(value)" class="record-facts">
      <div v-for="entry in entries" :key="entry.key" :class="{ 'record-wide': nested(entry.value) || /QrCode|qrCode/.test(entry.key), 'record-aggregate': /QrCode|qrCode/.test(entry.key) }">
        <dt>{{ label(entry.key) }}</dt><dd><RecordDetails v-if="nested(entry.value)" :value="entry.value" /><span v-else>{{ display(entry.value, entry.key) }}</span></dd>
      </div>
    </dl>
    <span v-else>{{ display(value) }}</span>
  </div>
</template>

<style scoped>
.record-details { min-width: 0; }.record-facts { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 0; margin: 0; border: 1px solid #e7eef7; border-radius: 10px; overflow: hidden; }
.record-facts > div { padding: 15px 17px; border-bottom: 1px solid #edf2f8; min-width: 0; }.record-facts dt { color: #8091a8; font-size: 12px; margin-bottom: 7px; }.record-facts dd { margin: 0; color: #304f73; font-size: 13px; line-height: 1.8; overflow-wrap: anywhere; white-space: pre-wrap; }.record-facts .record-wide { grid-column: 1 / -1; }.record-facts .record-aggregate { background: #f0f7ff; }.record-aggregate dd { font-weight: 600; color: #1677ff; }.record-empty { margin: 0; color: #8b9cb2; }.record-list { margin: 0; padding-left: 18px; }.record-value { overflow-wrap: anywhere; white-space: pre-wrap; }
@media(max-width:600px) { .record-facts { grid-template-columns: 1fr; } }
</style>
