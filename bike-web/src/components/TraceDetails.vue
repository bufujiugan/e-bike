<script setup lang="ts">
import { computed } from 'vue'
type Row = Record<string, string | number | null | undefined>
export type TraceData = {
  vehicle: Row; relatedVehicles: Row[]; bindingHistory: Row[]; events: Row[];
  stockInRecords: Row[]; stockOutRecords: Row[]; reworkRecords: Row[];
}
const props = defineProps<{ data: TraceData }>()
defineEmits<{ selectFrame: [frame: string] }>()
const vehicleFields = [
  ['frameSn', '车架 SN'], ['modelName', '车型'], ['skuCode', 'SKU'], ['batchNo', '批次'],
  ['statusName', '车辆状态'], ['motorSn', '电机 SN'], ['batterySn', '电池 SN'], ['displaySn', '仪表 SN'],
  ['vehicleQrCode', '当前聚合码'], ['assembleTime', '组装时间'], ['assembleOperatorName', '组装人员'],
  ['warehouseName', '当前仓库'], ['locationName', '当前库位'], ['customerName', '当前客户'], ['currentOrderNo', '当前订单'],
]
const sections = computed(() => [
  { title: '部件绑定历史', rows: props.data.bindingHistory, columns: [['partType','部件类型'],['partSn','部件 SN'],['action','动作'],['operationTime','时间'],['operatorName','操作人'],['reason','原因']] },
  { title: '生产与操作时间轴', rows: props.data.events, columns: [['operationTime','时间'],['action','操作'],['operatorName','操作人'],['partType','部件类型'],['partSn','部件 SN'],['reason','说明']] },
  { title: '入库记录', rows: props.data.stockInRecords, columns: [['stockInNo','入库单号'],['warehouseName','仓库'],['locationName','库位'],['operationTime','时间'],['status','状态'],['cancelReason','撤销原因']] },
  { title: '出库记录', rows: props.data.stockOutRecords, columns: [['stockOutNo','出库单号'],['piNo','PI编号'],['executionRevision','执行版本'],['dispatchBatchNo','出库批次'],['dispatchSourceType','批次来源'],['logisticsCarrier','承运商'],['logisticsTrackingNo','运单号'],['logisticsStatus','物流状态'],['customerName','客户'],['orderNo','订单'],['operationTime','时间'],['status','状态'],['cancelReason','撤销原因']] },
  { title: '返工换件', rows: props.data.reworkRecords, columns: [['reworkNo','返工单号'],['partType','部件类型'],['oldPartSn','旧件 SN'],['newPartSn','新件 SN'],['operationTime','时间'],['operatorName','操作人'],['reason','原因'],['status','状态']] },
])
const labels: Record<string,string> = { MOTOR:'电机', BATTERY:'电池', DISPLAY:'仪表', FRAME:'车架', COMPLETED:'已完成', CANCELLED:'已撤销', OPEN:'处理中', SHIPPED:'已发运', PLANNED:'计划中', WITHDRAWN:'已撤回', PI_COMMAND:'PI整批出库', ORDER_COMMAND:'订单批量出库', LEGACY_SINGLE:'历史单车记录（非原合批）' }
function display(value: Row[string]) { const text = value == null || value === '' ? '—' : String(value); return labels[text] ?? text.replace(/^(\d{4}-\d\d-\d\d)T/, '$1 ') }
</script>

<template>
  <div class="trace-details" aria-live="polite">
    <section class="trace-block">
      <h2>整车档案</h2>
      <dl class="trace-fields"><template v-for="[key,label] in vehicleFields" :key="key"><div><dt>{{ label }}</dt><dd>{{ display(data.vehicle[key]) }}</dd></div></template></dl>
    </section>
    <section v-if="data.relatedVehicles?.length" class="trace-block">
      <h2>关联车辆 <small>含部件曾经绑定的车辆</small></h2>
      <div class="trace-related"><button v-for="vehicle in data.relatedVehicles" :key="String(vehicle.id)" type="button" @click="$emit('selectFrame', String(vehicle.frameSn))">{{ vehicle.frameSn }} · {{ vehicle.statusName }}</button></div>
      <p class="trace-hint">部件查询的出入库与换件记录包含关联车辆。点击车架可查看该车独立的完整历史。</p>
    </section>
    <section v-for="section in sections" :key="section.title" class="trace-block">
      <h2>{{ section.title }} <small>{{ section.rows?.length ?? 0 }} 条</small></h2>
      <p v-if="!section.rows?.length" class="trace-hint">暂无记录</p>
      <div v-else class="trace-table"><table><thead><tr><th v-for="[key,label] in section.columns" :key="key">{{ label }}</th></tr></thead><tbody><tr v-for="(row,index) in section.rows" :key="index"><td v-for="[key] in section.columns" :key="key">{{ display(row[key]) }}</td></tr></tbody></table></div>
    </section>
  </div>
</template>

<style scoped>
.trace-details{display:grid;gap:16px;margin-top:20px;min-width:0;color:#23354a}.trace-block{padding:20px;background:#fff;border:1px solid #e0e7ef;border-radius:12px;min-width:0}.trace-block h2{font-size:17px;margin:0 0 16px}.trace-block small{font-size:12px;color:#6a7b8d;font-weight:400;margin-left:8px}.trace-fields{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:16px;margin:0}.trace-fields dt{color:#6a7b8d;font-size:12px;margin-bottom:5px}.trace-fields dd{margin:0;overflow-wrap:anywhere;font-size:14px}.trace-table{overflow-x:auto}.trace-table table{width:100%;border-collapse:collapse;font-size:13px}.trace-table th,.trace-table td{padding:11px 12px;text-align:left;border-bottom:1px solid #edf1f5;min-width:100px;max-width:360px;overflow-wrap:anywhere}.trace-table th{background:#f5f8fb;color:#52687d;white-space:nowrap}.trace-hint{color:#6a7b8d;font-size:13px;line-height:1.6}.trace-related{display:flex;gap:10px;flex-wrap:wrap}.trace-related button{border:1px solid #bed7eb;background:#f0f7fd;color:#1767a5;padding:10px;border-radius:7px;cursor:pointer}@media(max-width:600px){.trace-block{padding:14px}.trace-fields{grid-template-columns:1fr}.trace-table td{min-width:90px}.trace-block small{display:block;margin:5px 0}}
</style>
