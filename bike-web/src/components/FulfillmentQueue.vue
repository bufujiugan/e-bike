<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import api from '../api'
import { hasPermission, hasRole, type PermissionContext } from '../permissions'
import Pagination from './table/Pagination.vue'

const props = defineProps<{ kind: 'locks' | 'orders'; context: PermissionContext | null; refreshKey?: number }>()
const emit = defineEmits<{ convert: [lockNo: string]; order: [orderNo: string] }>()
const rows = ref<any[]>([]), total = ref(0), page = ref(1), keyword = ref(''), status = ref(''), busy = ref(false), error = ref('')
const linked = ref<any[]>([]), linkedTotal = ref(0), linkedPage = ref(1), linkedLock = ref<any>(), linkedBusy = ref(false), linkedError = ref('')
let version = 0, linkedVersion = 0
const canConvert = computed(() => !hasRole(props.context, 'WEB_AGENT') && !hasRole(props.context, 'PDA_OPERATOR') && hasPermission(props.context, 'ORDER_WRITE') && hasPermission(props.context, 'AGENT_LOCK_WRITE'))
const names: Record<string,string> = { LOCKED:'锁定中', PARTIAL_CONVERTED:'部分转订单', CONVERTED:'已转订单', RELEASED:'已释放', EXPIRED:'已到期', DRAFT:'草稿', PENDING:'待确认', CONFIRMED:'待出库', PARTIAL_SHIPPED:'部分出库', SHIPPED:'已完成', CANCELLED:'已作废', CLOSED:'已关闭' }
const time = (value?: string) => value ? value.replace('T', ' ').slice(0, 19) : '—'
const remaining = (row: any) => Math.max(0, row.lockQty - row.releasedQty - row.convertedQty)
async function load() {
  const current = ++version; busy.value = true; error.value = ''
  try {
    const response = (await api.get(props.kind === 'orders' ? '/api/orders' : '/api/inventory-locks', { params: { page:page.value, pageSize:20, ...(props.kind === 'orders' ? {keyword:keyword.value.trim() || undefined,status:status.value || undefined} : {}) } })).data.data
    if (current === version) { rows.value = response.records; total.value = response.total }
  } catch (cause) { if (current === version) { error.value = api.isAxiosError(cause) ? cause.response?.data?.message ?? '待办加载失败' : '待办加载失败'; rows.value = []; total.value = 0 } }
  finally { if (current === version) busy.value = false }
}
async function openLinked(row: any, targetPage = 1) {
  const current = ++linkedVersion; linkedLock.value = row; linkedPage.value = targetPage; linkedBusy.value = true; linkedError.value = ''; linked.value = []; linkedTotal.value = 0
  try {
    const result = (await api.get(`/api/inventory-locks/${row.id}/orders`, {params:{page:targetPage,pageSize:20}})).data.data
    if (current === linkedVersion) { linked.value = result.records; linkedTotal.value = result.total }
  } catch (cause) { if (current === linkedVersion) linkedError.value = api.isAxiosError(cause) ? cause.response?.data?.message ?? '关联订单加载失败' : '关联订单加载失败' }
  finally { if (current === linkedVersion) linkedBusy.value = false }
}
function search() { page.value = 1; void load() }
watch(() => [props.kind, props.refreshKey], () => { page.value = 1; void load() }, { immediate:true })
</script>
<template>
  <section class="fulfillment-queue sm-card">
    <div class="queue-toolbar"><div><h3>{{ kind === 'locks' ? '库存锁定 → 销售订单' : '销售订单 → 出库交付' }}</h3><p>按创建时间倒序 · {{ kind === 'locks' ? '查看锁定，原地转单并继续确认' : '打开一张订单，集中处理确认、PI 放行和出库核对' }}</p></div><el-button :loading="busy" @click="load">刷新</el-button></div>
    <form v-if="kind === 'orders'" class="queue-toolbar" @submit.prevent="search"><el-input v-model="keyword" placeholder="搜索订单号 / 客户" clearable /><el-select v-model="status" aria-label="订单状态" placeholder="全部状态" clearable><el-option v-for="key in ['DRAFT','PENDING','CONFIRMED','PARTIAL_SHIPPED','SHIPPED','CANCELLED','CLOSED']" :key="key" :value="key" :label="names[key]" /></el-select><el-button native-type="submit" type="primary">查询</el-button></form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table v-loading="busy" :data="rows" max-height="520" empty-text="暂无记录">
      <el-table-column :prop="kind === 'locks' ? 'lockNo' : 'orderNo'" label="单号" min-width="200" />
      <el-table-column v-if="kind === 'orders'" prop="customerName" label="客户" min-width="130" />
      <el-table-column v-if="kind === 'locks'" prop="skuCode" label="SKU" min-width="150" />
      <el-table-column prop="modelName" label="车型" min-width="120" />
      <el-table-column label="数量 / 进度" min-width="160"><template #default="{ row }">{{ kind === 'locks' ? `锁定 ${row.lockQty} · 可转 ${remaining(row)}` : `订购 ${row.orderQty} · 已出库 ${row.shippedQty}` }}</template></el-table-column>
      <el-table-column label="状态" min-width="110"><template #default="{row}">{{ names[row.status] ?? row.status }}</template></el-table-column>
      <el-table-column label="创建时间 ↓" min-width="170"><template #default="{row}">{{ time(row.createdAt) }}</template></el-table-column><el-table-column label="修改时间" min-width="170"><template #default="{row}">{{ time(row.updatedAt) }}</template></el-table-column>
      <el-table-column label="继续办理" width="185" fixed="right"><template #default="{row}"><template v-if="kind === 'locks'"><el-button v-if="canConvert && ['LOCKED','PARTIAL_CONVERTED'].includes(row.status) && remaining(row) > 0" link type="primary" @click="emit('convert',row.lockNo)">转订单</el-button><el-button v-if="hasPermission(context,'ORDER_READ')" link type="primary" @click="openLinked(row)">关联订单</el-button></template><el-button v-else link type="primary" @click="emit('order',row.orderNo)">打开订单办理</el-button></template></el-table-column>
    </el-table>
    <Pagination v-model:page="page" :total="total" :page-size="20" :disabled="busy" @change="load" />
    <el-dialog :model-value="!!linkedLock" title="本次锁定的关联订单" width="min(800px,96vw)" @close="linkedLock = undefined; linkedVersion++">
      <p>{{ linkedLock?.lockNo }}</p><el-alert v-if="linkedError" type="error" :title="linkedError" :closable="false" />
      <el-table v-loading="linkedBusy" :data="linked"><el-table-column prop="orderNo" label="订单号" min-width="200" /><el-table-column prop="customerName" label="客户" /><el-table-column prop="orderQty" label="数量" /><el-table-column label="操作" width="140"><template #default="{row}"><el-button link type="primary" @click="emit('order',row.orderNo); linkedLock = undefined">继续办理</el-button></template></el-table-column></el-table>
      <Pagination v-model:page="linkedPage" :total="linkedTotal" :page-size="20" :disabled="linkedBusy" @change="openLinked(linkedLock, linkedPage)" />
      <el-button v-if="linkedError" @click="openLinked(linkedLock, linkedPage)">重试</el-button>
    </el-dialog>
  </section>
</template>
<style scoped>
.fulfillment-queue{min-width:0;padding:20px}.queue-toolbar{display:flex;gap:12px;align-items:center;justify-content:space-between;flex-wrap:wrap;margin-bottom:18px}.queue-toolbar h3{margin:0}.queue-toolbar p{color:#64748b;margin-bottom:0}.queue-toolbar .el-input{flex:1;min-width:160px}.queue-toolbar .el-select{width:160px}
</style>
