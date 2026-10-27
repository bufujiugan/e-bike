<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { hasAnyPermission, hasPermission, hasRole, type PermissionContext } from '../permissions'
import { reservationLabel } from '../pi-api'
import RecordDetails from './RecordDetails.vue'
import PiWorkspace from './PiWorkspace.vue'

const props = defineProps<{ orderNo: string; context: PermissionContext | null }>()
const emit = defineEmits<{ close: []; changed: [] }>()
const order = ref<any>(), sourceLock = ref<any>(), busy = ref(false), saving = ref(false), error = ref(''), lockError = ref('')
const showPi = ref(false)
let version = 0
const internal = computed(() => !hasRole(props.context, 'WEB_AGENT') && !hasRole(props.context, 'PDA_OPERATOR'))
const canConfirm = computed(() => internal.value && hasPermission(props.context, 'ORDER_WRITE') && ['DRAFT', 'PENDING'].includes(order.value?.status))
const names: Record<string, string> = { DRAFT: '草稿', PENDING: '待确认', CONFIRMED: '待出库', PARTIAL_SHIPPED: '部分出库', SHIPPED: '已完成', CANCELLED: '已作废', CLOSED: '已关闭' }
const time = (value?: string) => value ? value.replace('T', ' ').slice(0, 19) : '—'
const nextStep = computed(() => {
  const current = order.value
  if (!current) return ''
  if (['DRAFT', 'PENDING'].includes(current.status)) return '下一步：核对客户、数量与库存占用，确认销售订单。'
  if (['CONFIRMED', 'PARTIAL_SHIPPED'].includes(current.status)) return '下一步：核对库存占用与 PI 发货放行，然后由 PDA 按本订单扫码出库。'
  if (current.status === 'SHIPPED') return '本订单已完成出库，可以在下方核对每台车及出库单。'
  return '该订单已停止履约，请核对订单状态及历史记录。'
})
async function load() {
  const current = ++version
  busy.value = true; error.value = ''; lockError.value = ''; order.value = undefined; sourceLock.value = undefined
  try {
    const result = (await api.get(`/api/orders/by-no/${encodeURIComponent(props.orderNo)}`)).data.data
    if (current !== version) return
    order.value = result
    if (result.sourceLockNo && hasAnyPermission(props.context, ['AGENT_LOCK_WRITE','AGENT_INVENTORY_READ']) && internal.value) {
      try {
        const lock = (await api.get(`/api/inventory-locks/by-no/${encodeURIComponent(result.sourceLockNo)}`)).data.data
        if (current === version) sourceLock.value = lock
      } catch (cause) { if (current === version) lockError.value = api.isAxiosError(cause) ? cause.response?.data?.message ?? '来源锁定加载失败' : '来源锁定加载失败' }
    }
  } catch (cause) { if (current === version) error.value = api.isAxiosError(cause) ? cause.response?.data?.message ?? '订单加载失败' : '订单加载失败' }
  finally { if (current === version) busy.value = false }
}
async function confirm() {
  if (!canConfirm.value || saving.value) return
  try { await ElMessageBox.confirm(`确认订单 ${order.value.orderNo}，共 ${order.value.orderQty} 台？`, '确认销售订单', { type: 'warning' }) }
  catch { return }
  saving.value = true
  try {
    await api.post(`/api/orders/${order.value.id}/confirm`)
    ElMessage.success('订单已确认，请继续核对发货条件'); emit('changed'); await load()
  } catch (cause) { ElMessage.error(api.isAxiosError(cause) ? cause.response?.data?.message ?? '订单确认失败，请刷新核对状态' : '订单确认失败，请刷新核对状态') }
  finally { saving.value = false }
}
watch(() => props.orderNo, () => { showPi.value = false; void load() }, { immediate: true })
</script>

<template>
  <el-dialog :model-value="true" title="订单办理 · 从确认到出库" width="min(1120px, 96vw)" :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving" @close="emit('close')">
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <div v-loading="busy" class="order-journey">
      <template v-if="order">
        <div class="journey-heading"><div><h2>{{ order.orderNo }}</h2><p>{{ order.customerName }} · {{ order.modelName }} · {{ names[order.status] ?? order.status }}</p></div><el-button :loading="busy" @click="load">刷新进度</el-button></div>
        <div class="journey-counts"><div>订购数量<strong>{{ order.orderQty }} 台</strong></div><div>已出库<strong>{{ order.shippedQty }} 台</strong></div><div>未出库<strong>{{ Math.max(0, order.orderQty - order.shippedQty) }} 台</strong></div><div>库存占用<strong>{{ reservationLabel(order.reservationStatus) }}</strong></div></div>
        <el-alert :title="nextStep" type="info" :closable="false" show-icon />
        <div class="journey-actions"><el-button v-if="canConfirm" type="primary" :loading="saving" @click="confirm">确认订单，继续出库</el-button><el-button v-if="hasPermission(context, 'PI_READ')" @click="showPi = !showPi">{{ showPi ? '收起 PI 与放行' : '本单 PI 与发货放行' }}</el-button></div>
        <p v-if="['CONFIRMED','PARTIAL_SHIPPED'].includes(order.status)">PDA：进入「出库」，选择客户「{{ order.customerName }}」，搜索订单「{{ order.orderNo }}」，核对后扫描整车总码。关联 PI 的订单还需完成 PI 确认、到账登记与发货放行。</p>
        <PiWorkspace v-if="showPi" :context="context" :initial-keyword="order.orderNo" />
        <el-descriptions title="订单与来源" :column="1" border>
          <el-descriptions-item label="来源锁定">{{ order.sourceLockNo || '无来源锁定' }}<span v-if="sourceLock"> · {{ sourceLock.skuCode || sourceLock.modelName }} · 锁定 {{ sourceLock.lockQty }} 台 · 已转 {{ sourceLock.convertedQty }} 台</span></el-descriptions-item>
          <el-descriptions-item label="计划出库日">{{ order.plannedShipDate || '—' }}</el-descriptions-item>
          <el-descriptions-item label="创建 / 修改">{{ time(order.createdAt) }} / {{ time(order.updatedAt) }}</el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="lockError" :title="lockError" type="warning" :closable="false" />
        <h3>本单已出库车辆（{{ order.shippedVehicles?.length ?? 0 }}）</h3>
        <el-table :data="order.shippedVehicles ?? []" max-height="360" empty-text="本单尚无有效出库车辆">
          <el-table-column prop="vehicleQrCode" label="总码" min-width="180" /><el-table-column prop="frameSn" label="车架码" min-width="170" /><el-table-column prop="stockOutNo" label="出库单号" min-width="180" /><el-table-column label="出库时间" min-width="170"><template #default="{row}">{{ time(row.stockOutTime) }}</template></el-table-column>
        </el-table>
        <el-collapse><el-collapse-item title="完整订单信息" name="details"><RecordDetails :value="order" /></el-collapse-item></el-collapse>
      </template>
    </div>
    <template #footer><el-button v-if="error" @click="load">重试</el-button><el-button :disabled="saving" @click="emit('close')">返回工作区</el-button></template>
  </el-dialog>
</template>

<style scoped>
.order-journey :deep(.pi-workspace){width:100%;max-width:100%;box-sizing:border-box;padding:16px;margin:16px 0}.order-journey :deep(.pi-heading){margin:0 0 16px;padding:12px 0}.order-journey :deep(.pi-filters){flex-wrap:wrap}.order-journey :deep(.pi-filters>*){max-width:100%}
.order-journey{min-height:120px;overflow-wrap:anywhere}.journey-heading,.journey-actions{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.journey-heading h2{margin:0}.journey-heading p,.order-journey>p{color:#64748b;line-height:1.8}.journey-counts{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin:18px 0}.journey-counts>div{background:#f1f6fb;padding:14px;border-radius:10px}.journey-counts strong{display:block;margin-top:8px;color:#1765ad}.journey-actions{justify-content:flex-start;margin:16px 0}.el-collapse{margin-top:18px}@media(max-width:600px){.journey-counts{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style>
