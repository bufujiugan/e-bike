<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { hasPermission, hasRole, type PermissionContext } from '../permissions'

const props = defineProps<{ context: PermissionContext | null }>()
const emit = defineEmits<{ navigate: [view: string] }>()
type Row = { skuId: number; modelId: number; skuCode: string; productName: string; specification: string; modelName: string; physicalQty: number; quarantinedQty: number; temporaryLockedQty: number; orderReservedQty: number; availableQty: number; shortageQty: number; legacyModelCommittedQty: number; modelAvailableQty: number; maxNewLockQty: number }
type Option = { id: number; name: string; code: string; status: string }
type Lock = { id: number; lockNo: string; skuCode: string; lockQty: number; expireTime: string }
const rows = ref<Row[]>([]), models = ref<Option[]>([]), customers = ref<Option[]>([]), agents = ref<Option[]>([])
const page = ref(1), total = ref(0), modelId = ref<number>(), busy = ref(false), error = ref('')
const selected = ref<Array<{ skuId: number; skuCode: string; quantity: number }>>([])
const canWrite = computed(() => !hasRole(props.context, 'WEB_AGENT') && hasPermission(props.context, 'AGENT_LOCK_WRITE'))
const canSeeLocks = computed(() => hasPermission(props.context, 'AGENT_INVENTORY_READ') || hasPermission(props.context, 'AGENT_LOCK_WRITE'))
const visible = ref(false), saving = ref(false), saveError = ref(''), result = ref<Lock[]>([])
const form = reactive({ customerId: undefined as number | undefined, agentId: undefined as number | undefined, expiresAt: '', remark: '' })
let retryKey = '', retryPayload = ''
function message(cause: unknown, fallback: string) { return api.isAxiosError(cause) ? cause.response?.data?.message ?? fallback : fallback }
async function load() {
  busy.value = true; error.value = ''
  try {
    const response = await api.get('/api/inventory/skus', { params: { modelId: modelId.value, page: page.value, pageSize: 20 } })
    rows.value = response.data.data.records; total.value = response.data.data.total
  } catch (cause) { error.value = message(cause, 'SKU库存加载失败'); rows.value = []; total.value = 0 }
  finally { busy.value = false }
}
function add(row: Row) {
  if (!selected.value.some(item => item.skuId === row.skuId)) selected.value.push({ skuId: row.skuId, skuCode: row.skuCode, quantity: 1 })
}
async function begin() {
  if (selected.value.length === 0) return
  saveError.value = ''; visible.value = true
  const responses = await Promise.allSettled(['customers', 'agents'].map(type => api.get(`/api/master/${type}`)))
  for (const [index, response] of responses.entries()) {
    if (response.status === 'rejected') { saveError.value = message(response.reason, '客户或代理商资料加载失败'); continue }
    const options: Option[] = response.value.data.data.filter((item: Option) => item.status === 'ENABLED')
    if (index === 0) customers.value = options; else agents.value = options
  }
}
async function save() {
  if (!form.customerId) { saveError.value = '请选择客户'; return }
  const body = { customerId: form.customerId, agentId: form.agentId ?? null, expiresAt: form.expiresAt || null, remark: form.remark || null,
    items: selected.value.map(({ skuId, quantity }) => ({ skuId, quantity })).sort((a, b) => a.skuId - b.skuId) }
  const fingerprint = JSON.stringify(body)
  if (fingerprint !== retryPayload) { retryPayload = fingerprint; retryKey = crypto.randomUUID() }
  saving.value = true; saveError.value = ''
  try {
    result.value = (await api.post('/api/sku-locks', { requestId: retryKey, ...body })).data.data
    selected.value = []; visible.value = false; retryKey = ''; retryPayload = ''
    ElMessage.success('SKU库存已锁定，可在库存锁定页面继续转订单')
    await load()
  } catch (cause) { saveError.value = message(cause, '锁定结果未确认，可重试当前内容') }
  finally { saving.value = false }
}
watch(modelId, () => { page.value = 1; void load() })
onMounted(async () => {
  await load()
  try { models.value = (await api.get('/api/master/models')).data.data }
  catch (cause) { ElMessage.error(message(cause, '车型筛选加载失败')) }
})
</script>

<template>
  <section class="sku-workspace">
    <header><div><h2>SKU 库存与锁定</h2><p>按具体规格核对库存，锁定后转单将保留所选 SKU。</p></div><el-button :loading="busy" @click="load">刷新库存</el-button></header>
    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon />
    <div class="toolbar"><el-select v-model="modelId" placeholder="全部车型" clearable filterable style="width:260px"><el-option v-for="model in models" :key="model.id" :value="model.id" :label="model.name" /></el-select><el-button v-if="canSeeLocks" @click="emit('navigate', hasRole(context, 'WEB_AGENT') ? 'agent-locks' : 'inventory-locks')">查看库存锁定</el-button></div>
    <p class="note">库存为全部仓库合计。“历史车型承诺”尚未分配到具体 SKU；当前可新增锁定量同时受该 SKU 和车型剩余库存限制，各 SKU 上限不能相加作为总可售量。</p>
    <el-table :data="rows" v-loading="busy" border>
      <el-table-column prop="skuCode" label="SKU" min-width="130" /><el-table-column prop="productName" label="商品" min-width="140" /><el-table-column prop="specification" label="规格" min-width="100" /><el-table-column prop="modelName" label="车型" min-width="100" />
      <el-table-column prop="physicalQty" label="在库" width="75" /><el-table-column prop="quarantinedQty" label="隔离" width="75" /><el-table-column prop="temporaryLockedQty" label="临时锁定" width="90" /><el-table-column prop="orderReservedQty" label="订单占用" width="90" /><el-table-column prop="availableQty" label="SKU净可用" width="105" />
      <el-table-column label="缺口" width="75"><template #default="{ row }"><span :class="{ shortage: row.shortageQty > 0 }">{{ row.shortageQty }}</span></template></el-table-column>
      <el-table-column prop="legacyModelCommittedQty" label="历史车型承诺" width="120" /><el-table-column prop="maxNewLockQty" label="可新增锁定" width="110" />
      <el-table-column v-if="canWrite" label="操作" width="100" fixed="right"><template #default="{ row }"><el-button link type="primary" :disabled="row.maxNewLockQty < 1 || selected.some(item => item.skuId === row.skuId)" @click="add(row)">加入锁定</el-button></template></el-table-column>
    </el-table>
    <el-pagination layout="total, prev, pager, next" :total="total" :page-size="20" v-model:current-page="page" @current-change="load" />
    <section v-if="canWrite && selected.length" class="selection"><h3>待锁定商品（{{ selected.length }} 个 SKU）</h3><div v-for="item in selected" :key="item.skuId" class="selected-row"><strong>{{ item.skuCode }}</strong><el-input-number v-model="item.quantity" :min="1" :max="1000000" :precision="0" /><el-button link type="danger" @click="selected = selected.filter(row => row.skuId !== item.skuId)">移除</el-button></div><el-button type="primary" @click="begin">选择客户并锁定</el-button></section>
    <section v-if="result.length" class="selection"><h3>本次锁定已保存</h3><el-table :data="result"><el-table-column prop="lockNo" label="锁定单号" min-width="180" /><el-table-column prop="skuCode" label="SKU" /><el-table-column prop="lockQty" label="数量" /><el-table-column prop="expireTime" label="截止时间" min-width="180" /></el-table><el-button @click="emit('navigate', 'inventory-locks')">进入库存锁定继续转单</el-button></section>
    <el-dialog v-model="visible" title="锁定所选 SKU" width="560px" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
      <el-alert v-if="saveError" type="error" :title="saveError" :closable="false" />
      <el-form label-width="100px" :disabled="saving">
        <el-form-item label="客户" required><el-select v-model="form.customerId" filterable placeholder="选择锁定所属客户"><el-option v-for="item in customers" :key="item.id" :label="`${item.name}（${item.code}）`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="代理商"><el-select v-model="form.agentId" clearable filterable placeholder="不选表示直接客户"><el-option v-for="item in agents" :key="item.id" :label="`${item.name}（${item.code}）`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="截止时间"><el-date-picker v-model="form.expiresAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="留空采用系统默认时长" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" maxlength="500" /></el-form-item>
      </el-form><p class="note">本次 {{ selected.length }} 个 SKU 将一起锁定。任一库存不足时全部不保存，转单客户须与锁定客户一致。</p>
      <template #footer><el-button :disabled="saving" @click="visible = false">返回</el-button><el-button type="primary" :loading="saving" @click="save">确认锁定</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.sku-workspace{padding:24px;background:#fff;border:1px solid #e3e9ef;border-radius:12px}.sku-workspace header,.toolbar,.selected-row{display:flex;align-items:center;justify-content:space-between;gap:16px}.sku-workspace h2{margin:0;color:#203446}.sku-workspace header p,.note{color:#6d7d89;font-size:13px;line-height:1.7}.toolbar{justify-content:flex-start;margin:20px 0 12px}.el-pagination{margin-top:18px}.selection{margin-top:22px;padding:18px;background:#f6f9fc;border-radius:8px}.selected-row{justify-content:flex-start;margin-bottom:12px}.selected-row strong{min-width:160px}.shortage{font-weight:700;color:#c1392b}.el-select{width:100%}.selection>.el-button{margin-top:12px}
</style>
