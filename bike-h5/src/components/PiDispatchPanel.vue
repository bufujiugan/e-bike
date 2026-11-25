<script setup lang="ts">
import { tr } from '../i18n/language'
import { createUuid } from '../utils/uuid'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useAutoVehicleScan } from '../composables/useAutoVehicleScan'
import { showConfirmDialog, showFailToast, showSuccessToast } from 'vant'
import api from '../api'

const props = withDefaults(defineProps<{ username: string; active?: boolean }>(), { active: true })
type Option = { id: number; piNo: string; customerName: string; customerId: number }
type Order = { orderId: number; orderNo: string; skuId?: number; skuCode?: string; status: string; orderedQty: number; shippedQty: number; closedQty: number; remainingQty: number }
type Detail = Option & { orders: Order[] }
type Scan = { orderId: number; vehicleQrCode: string; frameSn: string }
type Batch = { id: number; batchNo: string; status: string; originalQty: number; activeQty: number }
const rows = ref<Option[]>([]), total = ref(0), page = ref(1), keyword = ref(''), loading = ref(false), busy = ref(false), error = ref('')
const detail = ref<Detail>(), orderId = ref<number>(), qr = ref(''), queue = ref<Scan[]>([]), result = ref<Batch>()
const qrField = ref<{ focus: () => void } | null>(null)
let scanVersion = 0
const autoScan = useAutoVehicleScan(qr, () => props.active !== false && !busy.value && !!detail.value && !!orderId.value, scan)
watch([orderId, () => props.active], () => { ++scanVersion; autoScan.cancel(); qr.value = '' }, { flush: 'sync' })
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / 20)))
const pendingOrders = computed(() => {
  const grouped = new Map<number, { orderId: number; orderNo: string; remainingQty: number }>()
  for (const line of detail.value?.orders || []) {
    if (!['CONFIRMED', 'PARTIAL_SHIPPED'].includes(line.status) || line.remainingQty <= 0) continue
    const order = grouped.get(line.orderId)
    if (order) order.remainingQty += line.remainingQty
    else grouped.set(line.orderId, { orderId: line.orderId, orderNo: line.orderNo, remainingQty: line.remainingQty })
  }
  return grouped
})
const orderOptions = computed(() => [...pendingOrders.value.values()].map(order =>
  ({ text: `${order.orderNo} · ${tr('待发')}${order.remainingQty}`, value: order.orderId })))
let listVersion = 0, detailVersion = 0, disposed = false, requestId = '', fingerprint = ''
const storageKey = `bike_pi_dispatch:${props.username}`
function message(cause: unknown) { return api.isAxiosError(cause) ? cause.response?.data?.message || '请求失败，请重试' : cause instanceof Error ? cause.message : '操作失败' }
async function load(next = page.value) {
  const version = ++listVersion; loading.value = true; error.value = ''
  try {
    const response = await api.get('/api/pi-dispatch/options', { params: { keyword: keyword.value.trim() || undefined, page: next } })
    if (disposed || version !== listVersion) return
    rows.value = response.data.data.records; total.value = response.data.data.total; page.value = next
  } catch (cause) { if (!disposed && version === listVersion) error.value = message(cause) }
  finally { if (!disposed && version === listVersion) loading.value = false }
}
async function select(id: number, restoring = false) {
  if (busy.value || detail.value?.id === id) return
  if (queue.value.length && !restoring) {
    try { await showConfirmDialog({ title: tr('更换 PI'), message: tr('更换后清空当前扫码队列。尚未确认结果的批次应先按原内容重试核对。') }) } catch { return }
  }
  const version = ++detailVersion; busy.value = true; error.value = ''
  try {
    const response = await api.get(`/api/pi-dispatch/${id}`)
    if (disposed || version !== detailVersion) return
    detail.value = response.data.data; orderId.value = undefined; qr.value = ''; result.value = undefined
    if (!restoring) { queue.value = []; requestId = ''; fingerprint = ''; sessionStorage.removeItem(storageKey) }
  } catch (cause) { if (!disposed && version === detailVersion) error.value = message(cause) }
  finally { if (!disposed && version === detailVersion) busy.value = false }
}
function remember() { if (detail.value) sessionStorage.setItem(storageKey, JSON.stringify({ piId: detail.value.id, queue: queue.value, requestId, fingerprint })) }
async function scan() {
  autoScan.cancel()
  if (busy.value || props.active === false || !detail.value || !orderId.value || !qr.value.trim()) return
  const version = scanVersion
  const code = qr.value.trim(), order = pendingOrders.value.get(orderId.value)
  if (!order) return
  if (queue.value.some(item => item.vehicleQrCode === code)) { showFailToast(tr('同一整车不能重复扫码')); return }
  if (queue.value.length >= 200 || queue.value.filter(item => item.orderId === order.orderId).length >= order.remainingQty) { showFailToast(tr('已达到本单剩余数量或批次200台上限')); return }
  busy.value = true; error.value = ''
  try {
    const response = await api.post('/api/stock-out/validate', { customerId: detail.value.customerId, orderId: order.orderId, vehicleQrCode: code })
    if (disposed || version !== scanVersion) return
    const checked = response.data.data
    if (!checked.valid) throw new Error(checked.message || '车辆不可出库')
    if (queue.value.some(item => item.frameSn === checked.frameSn)) throw new Error('该车已通过其他二维码加入本批次')
    queue.value.push({ orderId: order.orderId, vehicleQrCode: code, frameSn: checked.frameSn }); qr.value = ''; remember(); showSuccessToast(tr('已加入批次'))
  } catch (cause) { if (!disposed && version === scanVersion) { error.value = message(cause); showFailToast(tr(error.value)) } }
  finally {
    if (!disposed) {
      busy.value = false
      if (version === scanVersion) void nextTick(() => qrField.value?.focus())
    }
  }
}
function remove(index: number) { if (!busy.value) { queue.value.splice(index, 1); remember() } }
async function submit() {
  if (busy.value || qr.value.trim() || !detail.value || !queue.value.length) return
  busy.value = true; error.value = ''
  const piId = detail.value.id
  const vehicles = queue.value.map(({ orderId, vehicleQrCode }) => ({ orderId, vehicleQrCode })).sort((a,b) => a.orderId - b.orderId || a.vehicleQrCode.localeCompare(b.vehicleQrCode))
  const nextFingerprint = JSON.stringify({ piId, vehicles })
  if (fingerprint !== nextFingerprint) { fingerprint = nextFingerprint; requestId = createUuid() }
  remember()
  try {
    const response = await api.post(`/api/pi-dispatch/${piId}`, { requestId, vehicles })
    if (disposed) return
    result.value = response.data.data; queue.value = []; requestId = ''; fingerprint = ''; sessionStorage.removeItem(storageKey)
    showSuccessToast(tr(result.value?.status === 'COMPLETED' ? '整批出库成功' : '已找到原批次，请核对撤销状态'))
    try { detail.value = (await api.get(`/api/pi-dispatch/${piId}`)).data.data } catch (cause) { error.value = `已保存批次，刷新履约数量失败：${message(cause)}` }
    orderId.value = undefined; await load()
  } catch (cause) { if (!disposed) error.value = message(cause) }
  finally { if (!disposed) busy.value = false }
}
onMounted(async () => {
  await load()
  try {
    const pending = JSON.parse(sessionStorage.getItem(storageKey) || 'null')
    if (pending && Number.isSafeInteger(pending.piId) && Array.isArray(pending.queue) && pending.queue.length <= 200 && pending.queue.every((item: Scan) => Number.isSafeInteger(item.orderId) && typeof item.vehicleQrCode === 'string' && typeof item.frameSn === 'string')) {
      await select(pending.piId, true)
      if (!disposed && detail.value?.id === pending.piId) { queue.value = pending.queue; requestId = typeof pending.requestId === 'string' ? pending.requestId : ''; fingerprint = typeof pending.fingerprint === 'string' ? pending.fingerprint : '' }
    }
  } catch { sessionStorage.removeItem(storageKey) }
})
onUnmounted(() => { disposed = true; ++listVersion; ++detailVersion })
</script>

<template>
  <section class="pi-dispatch">
    <van-notice-bar :text='tr("同一 PI 可扫描多种车型，一次提交整个批次。任一车辆不符合条件时全部不出库。")' wrapable />
    <van-field v-model="keyword" :label='tr("查找 PI")' :placeholder='tr("PI编号或客户")' :disabled="busy" @keyup.enter="load(1)"><template #button><van-button size="small" :loading="loading" :disabled="busy" @click="load(1)">{{ tr("查询") }}</van-button></template></van-field>
    <van-cell v-for="row in rows" :key="row.id" :title="row.piNo" :label="row.customerName" :value="detail?.id === row.id ? '已选择' : '选择'" clickable @click="select(row.id)" />
    <van-cell :title="tr('共 {value0} 份 · 第 {value1}/{value2} 页', { value0: total, value1: page, value2: pageCount })"><template #label><van-button size="mini" :disabled="loading || busy || page <= 1" @click="load(page-1)">{{ tr("上一页") }}</van-button> <van-button size="mini" :disabled="loading || busy || page >= pageCount" @click="load(page+1)">{{ tr("下一页") }}</van-button></template></van-cell>
    <template v-if="detail">
      <van-cell :title="detail.piNo" :label="detail.customerName" />
      <van-cell v-for="order in detail.orders" :key="`${order.orderId}:${order.skuId ?? ''}`" :title="order.skuCode || order.orderNo" :label="order.orderNo" :value="`已发${order.shippedQty} / 关闭${order.closedQty} / 待发${order.remainingQty}`" />
      <van-dropdown-menu :overlay="false"><van-dropdown-item v-model="orderId" :title="orderId ? undefined : tr('选择本次扫描的订单')" :options="orderOptions" :disabled="busy" /></van-dropdown-menu>
      <van-field ref="qrField" v-model="qr" :label='tr("整车总码")' :placeholder='tr("扫码或粘贴总码，自动加入")' :disabled="busy || !orderId" @keyup.enter="scan"><template #button><van-button size="small" :loading="busy" :disabled="!orderId || !qr.trim()" @click="scan">{{ tr("加入") }}</van-button></template></van-field>
      <p>{{ tr("完整总码扫码后自动校验加入，无需回车；核对清单后再确认出库。") }}</p>
      <van-cell :title="tr('本批次已扫 {value0} 台', { value0: queue.length })" />
      <van-cell v-for="(item,index) in queue" :key="item.vehicleQrCode" :title="item.vehicleQrCode" :label="detail.orders.find(order => order.orderId === item.orderId)?.orderNo"><template #right-icon><van-button size="mini" :disabled="busy" @click="remove(index)">{{ tr("移除") }}</van-button></template></van-cell>
      <van-button block type="primary" :disabled="!queue.length || !!qr.trim()" :loading="busy" @click="submit">{{ tr("确认 PI 整批出库（") }}{{ queue.length }} {{ tr("台）") }}</van-button>
    </template>
    <van-notice-bar v-if="error" :text="tr(error)" color="#c62828" background="#fff0f0" wrapable />
    <van-cell v-if="result" :title='tr("已保存真实出库批次")' :label="result.batchNo" :value="`有效${result.activeQty} / 原${result.originalQty}台`" />
  </section>
</template>
<style scoped>.pi-dispatch{display:grid;gap:10px}.pi-dispatch :deep(.van-cell__value){min-width:120px;overflow-wrap:anywhere}.pi-dispatch :deep(.van-cell__title){overflow-wrap:anywhere}</style>
