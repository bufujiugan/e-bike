<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { hasPermission, hasRole, isSuperAdmin, type PermissionContext } from '../permissions'
import AppIcon from './AppIcon.vue'
import Pagination from './table/Pagination.vue'

type VehicleTask = {
  id: string; workOrderId: number; status: string; frameSn?: string; motorSn?: string;
  batterySn?: string; displaySn?: string; qrCode?: string;
  scans?: { codeType: string; code: string }[];
}
type WorkOrder = {
  id: number; workOrderNo: string; skuCode: string; productName: string; batchNo: string;
  status: string; plannedQuantity: number; completedQuantity: number; inProgressQuantity: number;
  tasks: { records: VehicleTask[]; total: number };
}
type PrintJob = { id: string; status: string; reason?: string; createdAt?: string }
type LocationOption = { id: number; name: string; status: string }
type StockReceipt = { stockNo: string; warehouseName: string; locationName: string }
type Operation = 'scan' | 'prints' | 'stock'
const props = defineProps<{ workOrderId: number; context: PermissionContext | null; printRefreshKey: number }>()
const emit = defineEmits<{ close: []; changed: []; preview: [printId: string]; inspect: [task: VehicleTask] }>()
const workOrder = ref<WorkOrder>()
const taskPage = ref(1), listLoading = ref(false), listError = ref('')
const selectedTask = ref<VehicleTask>(), taskLoading = ref(false), taskError = ref('')
const printJobs = ref<PrintJob[]>([]), printLoading = ref(false), printError = ref('')
const operation = ref<Operation>('scan'), saving = ref(false), actionError = ref(''), actionMessage = ref('')
const scanCode = ref(''), expectedType = ref(''), scanOutcomeUnknown = ref(false)
const scanInput = ref<{ focus: () => void }>()
const warehouses = ref<LocationOption[]>([]), locations = ref<LocationOption[]>([])
const warehouseId = ref<number>(), locationId = ref<number>(), physicalQrCode = ref('')
const locationsLoading = ref(false), warehouseError = ref(''), stockReceipt = ref<StockReceipt>()
const codes = [
  { type: 'FRAME', key: 'frameSn', label: '车架码' }, { type: 'MOTOR', key: 'motorSn', label: '电机码' },
  { type: 'BATTERY', key: 'batterySn', label: '电池码' }, { type: 'DISPLAY', key: 'displaySn', label: '仪表码' },
] as const
const statusNames: Record<string, string> = { RELEASED: '可生产', PAUSED: '已暂停', COMPLETED: '四码已完成', OPEN: '作业中', CANCELLED: '已取消' }
const printNames: Record<string, string> = { PENDING: '待打印', PROCESSING: '已领取', SUBMITTED: '已提交打印', FAILED: '打印失败', UNKNOWN: '结果待核对' }
const stationAllowed = (type: string) => isSuperAdmin(props.context) || Boolean(props.context?.workstations.includes(type))
const canScan = computed(() => !hasRole(props.context, 'WEB_AGENT') && hasPermission(props.context, 'ASSEMBLY_OPERATE') && ['ASSEMBLY', ...codes.map(code => code.type)].some(stationAllowed))
const canStockIn = computed(() => !hasRole(props.context, 'WEB_AGENT') && hasPermission(props.context, 'STOCK_IN_OPERATE') && stationAllowed('STOCK_IN'))
const canManage = computed(() => !hasRole(props.context, 'WEB_AGENT') && !hasRole(props.context, 'PDA_OPERATOR') && hasPermission(props.context, 'PRODUCTION_MANAGE'))
const permittedCodes = computed(() => codes.filter(code => stationAllowed('ASSEMBLY') || stationAllowed(code.type)))
const canSubmitScan = computed(() => canScan.value && workOrder.value?.status === 'RELEASED' && !taskError.value && !scanOutcomeUnknown.value && (!selectedTask.value || selectedTask.value.status === 'OPEN'))
const operationLocked = computed(() => saving.value || taskLoading.value)
const completePercent = computed(() => workOrder.value?.plannedQuantity ? Math.min(100, Math.round(workOrder.value.completedQuantity / workOrder.value.plannedQuantity * 100)) : 0)
let listVersion = 0, taskVersion = 0, printVersion = 0, locationVersion = 0, disposed = false
function failureMessage(cause: unknown, fallback: string) {
  return api.isAxiosError(cause) && cause.response && cause.response.status < 500 ? cause.response.data?.message || fallback : fallback
}
function boundCode(key: typeof codes[number]['key'], type: string) {
  return selectedTask.value?.[key] || selectedTask.value?.scans?.find(scan => scan.codeType === type)?.code
}
async function loadWorkOrder() {
  const version = ++listVersion
  listLoading.value = true; listError.value = ''
  try {
    const result = (await api.get(`/api/production/work-orders/${props.workOrderId}`, { params: { taskPage: taskPage.value } })).data.data as WorkOrder
    if (!disposed && version === listVersion) workOrder.value = result
  } catch (cause) {
    if (!disposed && version === listVersion) listError.value = failureMessage(cause, '生产单加载失败，请重试。')
  } finally { if (!disposed && version === listVersion) listLoading.value = false }
}
async function loadPrints() {
  const taskId = selectedTask.value?.id, version = ++printVersion
  printJobs.value = []; printError.value = ''; printLoading.value = false
  if (!taskId) return
  printLoading.value = true
  try {
    const result = (await api.get(`/api/production/tasks/${encodeURIComponent(taskId)}/prints`)).data.data
    if (!disposed && version === printVersion && selectedTask.value?.id === taskId) printJobs.value = result
  } catch (cause) {
    if (!disposed && version === printVersion) printError.value = failureMessage(cause, '本车打印记录加载失败，请重试。')
  } finally { if (!disposed && version === printVersion) printLoading.value = false }
}
function resetVehicleMessages() {
  actionError.value = ''; actionMessage.value = ''; stockReceipt.value = undefined
  physicalQrCode.value = ''; scanCode.value = ''; taskError.value = ''
  printJobs.value = []; printError.value = ''; printVersion++; printLoading.value = false
}
async function selectVehicle(taskId: string) {
  if (saving.value) return
  const version = ++taskVersion
  selectedTask.value = undefined; resetVehicleMessages(); taskLoading.value = true
  try {
    const result = (await api.get(`/api/production/tasks/${encodeURIComponent(taskId)}`)).data.data as VehicleTask
    if (disposed || version !== taskVersion) return
    if (Number(result.workOrderId) !== props.workOrderId) throw new Error('WORK_ORDER_TASK_MISMATCH')
    selectedTask.value = result; scanOutcomeUnknown.value = false
    if (operation.value === 'prints') await loadPrints()
  } catch (cause) {
    if (!disposed && version === taskVersion) taskError.value = failureMessage(cause, '车辆读取失败，请重新选择本单车辆。')
  } finally { if (!disposed && version === taskVersion) taskLoading.value = false }
}
async function startNextVehicle() {
  if (operationLocked.value || !canScan.value || workOrder.value?.status !== 'RELEASED') return
  if (scanOutcomeUnknown.value) {
    try { await ElMessageBox.confirm('请先刷新并核对本单车辆。确认上次扫码未生成新的单车记录后，才开始新车。', '核对上次扫码结果', { confirmButtonText: '已核对，开始新车', cancelButtonText: '返回核对', type: 'warning' }) }
    catch (cause) { if (cause !== 'cancel' && cause !== 'close') actionError.value = '未能开始新车，请重新核对。'; return }
  }
  taskVersion++; selectedTask.value = undefined; resetVehicleMessages(); scanOutcomeUnknown.value = false
  operation.value = 'scan'; await nextTick(); scanInput.value?.focus()
}
async function scanVehicle() {
  if (operationLocked.value || !canSubmitScan.value || !scanCode.value.trim()) return
  saving.value = true; actionError.value = ''; actionMessage.value = ''
  try {
    const result = (await api.post(`/api/production/work-orders/${props.workOrderId}/scan`, {
      taskId: selectedTask.value?.id ?? null, code: scanCode.value.trim(), expectedType: expectedType.value || null,
    })).data.data as VehicleTask
    if (disposed) return
    selectedTask.value = result; scanCode.value = ''; scanOutcomeUnknown.value = false
    actionMessage.value = result.status === 'COMPLETED' ? '本车四码已完成，总码和打印任务已生成。可以继续打印，或开始下一台。' : '扫码成功，请继续扫描本车其他部件。'
    emit('changed'); await loadWorkOrder()
  } catch (cause) {
    if (disposed) return
    // 首码请求可能已经成功；网络失败后必须先核对本单车辆，不能自动重放创建操作。
    scanOutcomeUnknown.value = !api.isAxiosError(cause) || !cause.response || cause.response.status >= 500
    actionError.value = scanOutcomeUnknown.value ? '扫码结果未确认。请刷新本单车辆，选择已生成的车辆继续；不要直接重复首码。' : failureMessage(cause, '扫码失败，请核对编码。')
  } finally { if (!disposed) { saving.value = false; await nextTick(); scanInput.value?.focus() } }
}
async function loadWarehouses() {
  if (!canStockIn.value) return
  warehouseError.value = ''
  try { const result = (await api.get('/api/master/warehouses')).data.data; if (!disposed) warehouses.value = result.filter((item: LocationOption) => item.status === 'ENABLED') }
  catch (cause) { if (!disposed) warehouseError.value = failureMessage(cause, '仓库加载失败，请重试。') }
}
async function loadLocations() {
  const version = ++locationVersion, selectedWarehouseId = warehouseId.value
  locations.value = []; locationId.value = undefined; warehouseError.value = ''; locationsLoading.value = false
  if (!selectedWarehouseId) return
  locationsLoading.value = true
  try {
    const result = (await api.get(`/api/master/warehouses/${selectedWarehouseId}/locations`)).data.data
    if (!disposed && version === locationVersion) locations.value = result.filter((item: LocationOption) => item.status === 'ENABLED')
  } catch (cause) { if (!disposed && version === locationVersion) warehouseError.value = failureMessage(cause, '库位加载失败，请重新选择仓库。') }
  finally { if (!disposed && version === locationVersion) locationsLoading.value = false }
}
async function stockInVehicle() {
  const qrCode = selectedTask.value?.qrCode
  if (operationLocked.value || !canStockIn.value || !qrCode || !warehouseId.value || !locationId.value || stockReceipt.value) return
  if (physicalQrCode.value.trim() !== qrCode) { actionError.value = '实物总码与当前车辆不一致，请核对后重新扫描。'; return }
  saving.value = true; actionError.value = ''; actionMessage.value = ''
  let submitted = false
  try {
    const validation = (await api.post('/api/stock-in/validate', { vehicleQrCode: qrCode })).data.data
    if (!validation.valid) { actionError.value = validation.message || '当前车辆不允许入库。'; return }
    // 校验只用于提示，最终入库仍由原接口在事务内检查车辆状态及仓库、库位。
    submitted = true
    const receipt = (await api.post('/api/stock-in', { vehicleQrCode: qrCode, warehouseId: warehouseId.value, locationId: locationId.value })).data.data
    if (disposed) return
    stockReceipt.value = receipt; actionMessage.value = '本车已入库。可在左侧选择下一台继续办理。'; emit('changed')
  } catch (cause) {
    if (!disposed) actionError.value = failureMessage(cause, submitted ? '入库结果未确认，请先核对库存；再次提交时系统会重新检查车辆状态。' : '入库校验失败，请重试。')
  } finally { if (!disposed) saving.value = false }
}
async function changeStatus() {
  if (operationLocked.value || !canManage.value || !workOrder.value || !['RELEASED', 'PAUSED'].includes(workOrder.value.status)) return
  saving.value = true
  const action = workOrder.value.status === 'PAUSED' ? 'resume' : 'pause'
  try {
    const result = await ElMessageBox.prompt('请填写原因，操作会影响本单现场扫码。', action === 'pause' ? '暂停生产单' : '恢复生产单', { inputValidator: value => Boolean(value?.trim()) || '原因不能为空' })
    await api.post(`/api/production/work-orders/${props.workOrderId}/${action}`, { reason: result.value.trim() })
    ElMessage.success(action === 'pause' ? '生产单已暂停' : '生产单已恢复'); emit('changed'); await loadWorkOrder()
  } catch (cause) { if (cause !== 'cancel' && cause !== 'close') actionError.value = failureMessage(cause, '状态变更未确认，请刷新生产单核对。') }
  finally { if (!disposed) saving.value = false }
}
watch(operation, next => { if (next === 'prints') void loadPrints(); if (next === 'stock') void loadWarehouses() })
watch(warehouseId, () => { void loadLocations() })
watch(() => props.printRefreshKey, () => { if (operation.value === 'prints') void loadPrints() })
onMounted(() => { void loadWorkOrder() })
onBeforeUnmount(() => { disposed = true; listVersion++; taskVersion++; printVersion++; locationVersion++ })
</script>

<template>
  <div class="production-order-workbench" v-loading="listLoading && !workOrder">
    <header class="order-topline"><el-button :disabled="saving" @click="emit('close')">← 返回生产单列表</el-button><span>一张生产单，从扫码到入库</span><div><el-button :disabled="operationLocked" :loading="listLoading" @click="loadWorkOrder">刷新本单</el-button><el-button v-if="canManage && workOrder && ['RELEASED', 'PAUSED'].includes(workOrder.status)" :disabled="operationLocked" @click="changeStatus">{{ workOrder.status === 'PAUSED' ? '恢复生产' : '暂停生产' }}</el-button></div></header>
    <el-alert v-if="listError" :title="listError" type="error" :closable="false" show-icon />
    <template v-if="workOrder">
      <section class="order-summary"><div><span class="order-eyebrow">当前生产单</span><div class="order-title"><h2>{{ workOrder.workOrderNo }}</h2><el-tag :type="workOrder.status === 'PAUSED' ? 'warning' : 'primary'">{{ statusNames[workOrder.status] || workOrder.status }}</el-tag></div><p>{{ workOrder.productName }} · {{ workOrder.skuCode }} · 批次 {{ workOrder.batchNo }}</p></div><div class="order-progress"><div><span>四码完成进度</span><strong>{{ workOrder.completedQuantity }} / {{ workOrder.plannedQuantity }} 台</strong></div><el-progress :percentage="completePercent" :show-text="false" /><small>作业中 {{ workOrder.inProgressQuantity }} 台 · 完成扫码后仍需打印、入库</small></div></section>
      <nav class="order-operations" aria-label="本生产单办理环节"><button v-for="step in [{ key: 'scan', title: '扫码生产', detail: '绑定本车四码', icon: 'trace' }, { key: 'prints', title: '标签打印', detail: '本车标签与打印记录', icon: 'printer' }, { key: 'stock', title: '办理入库', detail: '核对实物与库位', icon: 'stock-in' }] as const" :key="step.key" :disabled="operationLocked" :class="{ active: operation === step.key }" :aria-pressed="operation === step.key" @click="operation = step.key"><AppIcon :name="step.icon" /><span><strong>{{ step.title }}</strong><small>{{ step.detail }}</small></span><AppIcon name="chevron" /></button></nav>
      <div class="order-body">
        <aside class="order-vehicles"><div class="vehicles-heading"><h3>本单车辆 <small>{{ workOrder.tasks?.total ?? 0 }}</small></h3><el-button v-if="canScan" link type="primary" :disabled="operationLocked || workOrder.status !== 'RELEASED'" @click="startNextVehicle">＋ 开始新车</el-button></div><p>选择一台车，右侧连续办理。</p><el-button :loading="listLoading" :disabled="saving" size="small" @click="loadWorkOrder">刷新车辆列表</el-button><div class="vehicle-choices"><button v-for="vehicle in workOrder.tasks?.records ?? []" :key="vehicle.id" :disabled="operationLocked" :class="{ selected: selectedTask?.id === vehicle.id }" @click="selectVehicle(vehicle.id)"><strong>{{ vehicle.frameSn || vehicle.motorSn || vehicle.batterySn || vehicle.displaySn || vehicle.id }}</strong><span>{{ statusNames[vehicle.status] || vehicle.status }} <i>{{ vehicle.qrCode ? '已生成总码' : '待绑定完整' }}</i></span></button><p v-if="!workOrder.tasks?.total" class="vehicle-empty">尚未开始生产。首码扫描后自动生成本单车辆，无需逐车建单。</p></div><Pagination v-model:page="taskPage" :total="workOrder.tasks?.total ?? 0" :page-size="50" :disabled="saving || listLoading" @change="loadWorkOrder" /></aside>
        <section class="order-action-panel" v-loading="taskLoading">
          <div class="current-vehicle"><div><span>{{ selectedTask ? '当前办理车辆' : canScan && operation === 'scan' ? '准备开始新车' : '请选择本单车辆' }}</span><strong>{{ selectedTask?.frameSn || selectedTask?.id || (canScan && operation === 'scan' ? '扫描第一个部件码，自动建立单车记录' : '选择一台车，继续办理当前环节') }}</strong><small v-if="selectedTask?.qrCode">总码：{{ selectedTask.qrCode }}</small></div><el-tag v-if="selectedTask">{{ statusNames[selectedTask.status] || selectedTask.status }}</el-tag></div>
          <el-button v-if="selectedTask" class="vehicle-history" link :disabled="operationLocked" @click="emit('inspect', selectedTask)">查看本车生产与流转履历</el-button>
          <el-alert v-if="taskError" :title="taskError" type="error" :closable="false" show-icon />
          <el-alert v-if="actionError" :title="actionError" type="error" :closable="false" show-icon />
          <el-alert v-if="actionMessage" :title="actionMessage" type="success" :closable="false" show-icon />
          <template v-if="operation === 'scan'">
            <div class="vehicle-code-grid"><div v-for="code in codes" :key="code.type" :class="{ bound: boundCode(code.key, code.type) }"><span><AppIcon :name="boundCode(code.key, code.type) ? 'check' : 'trace'" />{{ code.label }}</span><strong>{{ boundCode(code.key, code.type) || '待扫描' }}</strong></div></div>
            <el-alert v-if="!canScan" title="当前账号没有生产扫码权限或未配置生产工位，可在本页查看进度。" type="info" :closable="false" />
            <el-alert v-else-if="workOrder.status !== 'RELEASED'" :title="workOrder.status === 'PAUSED' ? '生产单已暂停，恢复后才能继续扫码。' : '本单四码生产已完成，可以继续标签打印和入库。'" type="info" :closable="false" />
            <form v-if="canScan && workOrder.status === 'RELEASED' && (!selectedTask || selectedTask.status === 'OPEN')" class="scan-form" @submit.prevent="scanVehicle"><label>本次扫描类型</label><el-select v-model="expectedType" aria-label="本次扫描类型" placeholder="自动识别部件类型" :disabled="operationLocked || scanOutcomeUnknown"><el-option label="自动识别部件类型" value="" /><el-option v-for="code in permittedCodes" :key="code.type" :label="code.label" :value="code.type" /></el-select><label>扫描部件码</label><el-input ref="scanInput" v-model="scanCode" aria-label="扫描部件码" placeholder="使用扫码枪扫描，或输入编码后按回车" maxlength="255" :disabled="operationLocked || scanOutcomeUnknown" /><el-button type="primary" native-type="submit" :loading="saving" :disabled="!canSubmitScan || !scanCode.trim() || taskLoading">确认本次扫描</el-button><p>四码可按工位任意顺序绑定；同一台车完成前，请勿混入下一台的部件。</p></form>
            <div v-if="selectedTask?.qrCode" class="next-operation"><div><strong>本车已生成总码</strong><p>下一步核对标签并打印，再办理实物入库。</p></div><el-button type="primary" :disabled="operationLocked" @click="operation = 'prints'">继续：标签打印 →</el-button></div>
          </template>
          <template v-else-if="operation === 'prints'">
            <div class="panel-heading"><h3>本车打印任务</h3><el-button :disabled="!selectedTask || operationLocked" :loading="printLoading" @click="loadPrints">刷新打印记录</el-button></div><el-alert v-if="printError" :title="printError" type="error" :closable="false" /><el-empty v-if="!selectedTask" description="先在左侧选择本单车辆，再查看它的标签。" :image-size="70" /><template v-else><el-table v-loading="printLoading" :data="printJobs" empty-text="暂无打印任务，四码完成后自动生成。"><el-table-column prop="id" label="打印任务" min-width="140" show-overflow-tooltip /><el-table-column label="状态" width="115"><template #default="{ row }">{{ printNames[row.status] || row.status }}</template></el-table-column><el-table-column prop="reason" label="原因" min-width="100" /><el-table-column label="操作" width="120"><template #default="{ row }"><el-button link type="primary" :disabled="operationLocked" @click="emit('preview', row.id)">预览 / 打印</el-button></template></el-table-column></el-table><p class="panel-note">打印状态以任务回报为准。提交打印后仍需核对实物标签。</p><div v-if="selectedTask.qrCode" class="next-operation"><div><strong>核对实物标签后继续</strong><p>当前车辆会自动带入入库环节。</p></div><el-button type="primary" :disabled="operationLocked" @click="operation = 'stock'">继续：办理入库 →</el-button></div></template>
          </template>
          <template v-else>
            <h3>本车入库</h3><el-alert v-if="!canStockIn" title="当前账号没有入库权限或入库工位，请由入库人员在本生产单继续办理。" type="info" :closable="false" /><el-empty v-else-if="!selectedTask?.qrCode" description="先选择已完成四码、已生成总码的本单车辆。" :image-size="70" /><template v-else><el-alert v-if="warehouseError" :title="warehouseError" type="error" :closable="false" /><el-button v-if="warehouseError" :disabled="saving" @click="loadWarehouses(); loadLocations()">重试仓库与库位</el-button><el-form label-position="top" class="stock-in-form" @submit.prevent="stockInVehicle"><el-form-item label="入库仓库" required><el-select v-model="warehouseId" aria-label="入库仓库" placeholder="选择仓库" :disabled="operationLocked || !!stockReceipt"><el-option v-for="warehouse in warehouses" :key="warehouse.id" :value="warehouse.id" :label="warehouse.name" /></el-select></el-form-item><el-form-item label="入库库位" required><el-select v-model="locationId" aria-label="入库库位" placeholder="选择库位" :loading="locationsLoading" :disabled="operationLocked || !warehouseId || locationsLoading || !!stockReceipt"><el-option v-for="location in locations" :key="location.id" :value="location.id" :label="location.name" /></el-select></el-form-item><el-form-item label="核对实物总码" required><el-input v-model="physicalQrCode" aria-label="核对实物总码" placeholder="扫描实物标签，必须与当前车辆总码一致" :disabled="operationLocked || !!stockReceipt" /></el-form-item><el-button type="primary" native-type="submit" :loading="saving" :disabled="!warehouseId || !locationId || locationsLoading || !physicalQrCode.trim() || !!stockReceipt || taskLoading">确认本车入库</el-button></el-form><div v-if="stockReceipt" class="stock-receipt"><AppIcon name="check" /><div><strong>入库成功 · {{ stockReceipt.stockNo }}</strong><p>{{ stockReceipt.warehouseName }} / {{ stockReceipt.locationName }}</p></div></div></template>
          </template>
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.vehicle-history { margin: -6px 0 16px; }
.production-order-workbench{display:grid;gap:18px;min-width:0;color:#25425e}.order-topline{display:flex;align-items:center;gap:16px;flex-wrap:wrap}.order-topline>span{font-size:12px;color:#8394a5}.order-topline>div{margin-left:auto;display:flex;gap:8px}.order-summary{display:flex;align-items:center;justify-content:space-between;gap:24px;padding:25px;background:#fff;border:1px solid #dce6f0;border-radius:12px}.order-eyebrow{font-size:11px;letter-spacing:1px;color:#7d93aa}.order-title{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:10px 0}.order-title h2{margin:0;font-size:24px;overflow-wrap:anywhere}.order-summary p{margin:0;color:#70869d;font-size:13px;line-height:1.8}.order-progress{min-width:230px;max-width:320px;flex:1}.order-progress>div:first-child{display:flex;justify-content:space-between;gap:12px;font-size:12px;margin-bottom:12px}.order-progress small{display:block;margin-top:9px;color:#8b9bab;font-size:11px}.order-operations{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.order-operations button{display:flex;align-items:center;gap:13px;padding:17px 20px;background:#fff;border:1px solid #dce6f0;border-radius:9px;color:#70859b;text-align:left;cursor:pointer}.order-operations button.active{border-color:#2368cb;background:#edf4ff;color:#2368cb}.order-operations button>span{flex:1}.order-operations strong,.order-operations small{display:block}.order-operations strong{font-size:14px}.order-operations small{font-size:11px;margin-top:5px;color:#7f94ab}.order-operations .app-icon{width:22px;height:22px}.order-operations .app-icon:last-child{width:16px}.order-body{display:grid;grid-template-columns:290px minmax(0,1fr);gap:18px;align-items:start}.order-vehicles,.order-action-panel{min-width:0;border:1px solid #dce6f0;background:#fff;border-radius:12px;padding:20px}.vehicles-heading,.panel-heading{display:flex;justify-content:space-between;align-items:center;gap:10px}.vehicles-heading h3,.panel-heading h3{margin:0;font-size:15px}.vehicles-heading small{color:#8a9aad;font-size:12px;font-weight:400}.order-vehicles>p,.panel-note{font-size:12px;color:#8a9aad;line-height:1.8}.vehicle-choices{display:grid;gap:8px;max-height:460px;overflow:auto;margin:16px 0}.vehicle-choices button{display:grid;gap:8px;text-align:left;border:1px solid #e5ebf2;border-radius:7px;background:#f8fafc;padding:12px;color:#45617d;cursor:pointer}.vehicle-choices button.selected{background:#edf4ff;border-color:#6fa0df;color:#2368cb}.vehicle-choices strong{font-size:12px;overflow-wrap:anywhere}.vehicle-choices span{font-size:11px;display:flex;gap:8px;justify-content:space-between}.vehicle-choices i{font-style:normal;color:#8c9bad}.vehicle-empty{font-size:12px;color:#8698aa;line-height:1.9}.order-vehicles :deep(.sm-pagination){display:flex;flex-wrap:wrap;padding:8px 0;gap:10px}.order-vehicles :deep(.el-pagination){flex-wrap:wrap}.order-vehicles :deep(.el-pager){display:none}.current-vehicle{display:flex;align-items:center;justify-content:space-between;gap:16px;padding-bottom:18px;border-bottom:1px solid #e9eef5;margin-bottom:18px}.current-vehicle span{color:#8c9db0;font-size:11px}.current-vehicle strong{display:block;font-size:15px;margin-top:7px;overflow-wrap:anywhere}.current-vehicle small{display:block;color:#6d83a0;font-size:12px;margin-top:8px;overflow-wrap:anywhere}.order-action-panel>.el-alert{margin-bottom:14px}.vehicle-code-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;margin:18px 0}.vehicle-code-grid>div{padding:14px;border:1px solid #e5ebf2;border-radius:8px;background:#f8fafc}.vehicle-code-grid span{display:flex;align-items:center;gap:7px;color:#8598ac;font-size:12px}.vehicle-code-grid .app-icon{width:16px;height:16px}.vehicle-code-grid strong{display:block;overflow-wrap:anywhere;font-size:13px;margin-top:10px;color:#7d91a5}.vehicle-code-grid .bound{background:#f0faf5;border-color:#cbe6d9}.vehicle-code-grid .bound strong,.vehicle-code-grid .bound span{color:#278463}.scan-form{display:grid;gap:12px;margin-top:20px}.scan-form label{font-size:12px;color:#6b829a}.scan-form .el-button{width:max-content;margin-top:6px}.scan-form p{font-size:12px;color:#8799ab;line-height:1.8;margin:0}.next-operation{display:flex;justify-content:space-between;align-items:center;gap:16px;flex-wrap:wrap;margin-top:24px;padding:18px;background:#f0f6ff;border-radius:9px}.next-operation strong{font-size:14px}.next-operation p{margin:8px 0 0;font-size:12px;color:#7a91aa;line-height:1.7}.panel-heading{margin-bottom:16px}.stock-in-form{margin-top:20px;max-width:520px}.stock-in-form .el-select{width:100%}.stock-receipt{display:flex;gap:12px;align-items:center;margin-top:20px;padding:16px;background:#edf8f1;color:#25845a;border-radius:8px}.stock-receipt .app-icon{width:24px}.stock-receipt strong{font-size:14px;overflow-wrap:anywhere}.stock-receipt p{font-size:12px;margin-bottom:0}.production-order-workbench button:focus-visible{outline:3px solid #85b9ff;outline-offset:2px}.production-order-workbench button:disabled{cursor:not-allowed;opacity:.6}
@media(max-width:1100px){.order-body{grid-template-columns:240px minmax(0,1fr)}.order-vehicles,.order-action-panel{padding:16px}.order-operations button{padding:14px;gap:8px}.order-operations small{display:none}.order-summary{flex-wrap:wrap}.order-progress{max-width:none}}@media(max-width:760px){.order-body{grid-template-columns:minmax(0,1fr)}.vehicle-choices{max-height:180px}.order-topline>span{display:none}.order-summary{padding:18px}.order-title h2{font-size:19px}.order-operations{gap:6px}.order-operations button{padding:12px 8px;justify-content:center}.order-operations .app-icon{display:none}.order-operations strong{font-size:12px}.vehicle-code-grid{grid-template-columns:minmax(0,1fr)}}
</style>
