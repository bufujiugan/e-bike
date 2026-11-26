<script setup lang="ts">
import { tr, trInline } from '../i18n/language'
import RecordIdColumn from './table/RecordIdColumn.vue'
import { createUuid } from '../utils/uuid'
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, type ButtonInstance } from 'element-plus'
import api from '../api'
import { hasPermission, hasRole, isSuperAdmin, type PermissionContext } from '../permissions'
import SkuInventoryOverview from './business/SkuInventoryOverview.vue'
import SectionHeader from './common/SectionHeader.vue'
import FilterBar from './common/FilterBar.vue'
import AlertCard from './common/AlertCard.vue'
import DataTable from './table/DataTable.vue'
import Pagination from './table/Pagination.vue'
import AppIcon from './AppIcon.vue'

const props = defineProps<{ context: PermissionContext | null; initialSkuId?: number; initialModelId?: number }>()
const emit = defineEmits<{ navigate: [view: string]; convert: [lockNo: string] }>()
type Row = { skuId: number; modelId: number; skuCode: string; productName: string; specification: string; modelName: string; physicalQty: number; quarantinedQty: number; temporaryLockedQty: number; orderReservedQty: number; availableQty: number; shortageQty: number; legacyModelCommittedQty: number; modelAvailableQty: number; maxNewLockQty: number }
type Option = { id: number; name: string; code: string; status: string }
type Lock = { id: number; lockNo: string; skuCode: string; lockQty: number }
const rows = ref<Row[]>([]), models = ref<Option[]>([]), customers = ref<Option[]>([]), agents = ref<Option[]>([])
const page = ref(1), total = ref(0), modelId = ref<number | undefined>(Number.isFinite(props.initialModelId) ? props.initialModelId : undefined), busy = ref(false), error = ref('')
const focusedSkuId = ref(props.initialSkuId)
const canConvert = computed(() => canWrite.value && !hasRole(props.context, 'PDA_OPERATOR') && hasPermission(props.context, 'ORDER_WRITE'))
const maxLockQuantity = 1_000_000
const selected = ref<Array<{ skuId: number; skuCode: string; quantity: number | null }>>([])
const selectedQuantity = computed(() => selected.value.reduce((total, item) => total + (item.quantity ?? 0), 0))
const hasValidQuantities = computed(() => selected.value.length > 0 &&
  selected.value.every(({ quantity }) => quantity !== null && Number.isInteger(quantity) &&
    quantity >= 1 && quantity <= maxLockQuantity))
const canWrite = computed(() => !hasRole(props.context, 'WEB_AGENT') && hasPermission(props.context, 'AGENT_LOCK_WRITE'))
const canSeeLocks = computed(() => hasPermission(props.context, 'AGENT_INVENTORY_READ') || hasPermission(props.context, 'AGENT_LOCK_WRITE'))
const canCreateMasterData = computed(() => isSuperAdmin(props.context))
const visible = ref(false), saving = ref(false), saveError = ref(''), result = ref<Lock[]>([])
const dialogStep = ref<'confirm' | 'result'>('confirm')
const doneButton = ref<ButtonInstance>()
const resultQuantity = computed(() => result.value.reduce((total, lock) => total + lock.lockQty, 0))
const resultTitle = computed(() => tr('预锁已登记 · 1 批，{count} 个 SKU，共 {quantity} 台', {
  count: result.value.length,
  quantity: resultQuantity.value,
}))
const form = reactive({ customerId: undefined as number | undefined, agentId: undefined as number | undefined, expiresAt: '', remark: '' })
let retryKey = '', retryPayload = ''
function message(cause: unknown, fallback: string) { return api.isAxiosError(cause) ? cause.response?.data?.message ?? fallback : fallback }
async function load() {
  busy.value = true; error.value = ''
  try {
    if (focusedSkuId.value) {
      // 现有库存接口按车型分页，逐页寻找目标 SKU，避免把第一页当作全部库存。
      let currentPage = 1, found: Row | undefined
      while (true) {
        const result = (await api.get('/api/inventory/skus', {params:{modelId:modelId.value,page:currentPage,pageSize:100}})).data.data
        found = result.records.find((row: Row) => row.skuId === focusedSkuId.value)
        if (found || currentPage * result.pageSize >= result.total || !result.records.length) break
        currentPage++
      }
      rows.value = found ? [found] : []; total.value = rows.value.length
    } else {
      const response = await api.get('/api/inventory/skus', { params: { modelId: modelId.value, page: page.value, pageSize: 20 } })
      rows.value = response.data.data.records; total.value = response.data.data.total
    }
  } catch (cause) { error.value = message(cause, 'SKU库存加载失败'); rows.value = []; total.value = 0 }
  finally { busy.value = false }
}
function add(row: Row) {
  if (!selected.value.some(item => item.skuId === row.skuId)) selected.value.push({ skuId: row.skuId, skuCode: row.skuCode, quantity: 1 })
}
function navigateToMasterData(view: 'customers' | 'agents') {
  visible.value = false
  emit('navigate', view)
}
async function begin() {
  if (!hasValidQuantities.value) return
  dialogStep.value = 'confirm'
  saveError.value = ''; visible.value = true
  const responses = await Promise.allSettled(['customers', 'agents'].map(type => api.get(`/api/master/${type}`)))
  for (const [index, response] of responses.entries()) {
    if (response.status === 'rejected') { saveError.value = message(response.reason, '订单客户或所属客户资料加载失败'); continue }
    const options: Option[] = response.value.data.data.filter((item: Option) => item.status === 'ENABLED')
    if (index === 0) customers.value = options; else agents.value = options
  }
}
async function save() {
  if (saving.value || dialogStep.value !== 'confirm' || !hasValidQuantities.value) return
  if (!form.customerId) { saveError.value = '请选择客户'; return }
  const body = { customerId: form.customerId, agentId: form.agentId ?? null, expiresAt: form.expiresAt || null, remark: form.remark || null,
    items: selected.value.map(({ skuId, quantity }) => ({ skuId, quantity })).sort((a, b) => a.skuId - b.skuId) }
  const fingerprint = JSON.stringify(body)
  if (fingerprint !== retryPayload) { retryPayload = fingerprint; retryKey = createUuid() }
  saving.value = true; saveError.value = ''
  try {
    result.value = (await api.post('/api/sku-locks', { requestId: retryKey, ...body })).data.data
    selected.value = []; retryKey = ''; retryPayload = ''
    dialogStep.value = 'result'
    void load()
    await nextTick()
    doneButton.value?.ref?.focus()
  } catch (cause) { saveError.value = message(cause, '锁定结果未确认，可重试当前内容') }
  finally { saving.value = false }
}
function showResult() {
  dialogStep.value = 'result'
  visible.value = true
}
watch(modelId, () => { focusedSkuId.value = undefined; page.value = 1; void load() })
onMounted(async () => {
  await load()
  try { models.value = (await api.get('/api/master/models')).data.data }
  catch (cause) { ElMessage.error(tr(message(cause, '车型筛选加载失败'))) }
})
</script>

<template>
  <section class="sku-workspace sm-page">
    <el-alert v-if="focusedSkuId" :title='tr("已定位当前商品 SKU；这里显示该 SKU 的全部仓库合计库存，不限定某张生产工单。")' type="info" :closable="false" />
    <SkuInventoryOverview :rows="rows" :loading="busy" :error="error" />
    <el-alert v-if="error" type="error" :title="tr(error)" :closable="false" show-icon />
    <section class="sm-card">
    <FilterBar>
      <el-select v-model="modelId" :aria-label='tr("车型筛选")' :placeholder='tr("全部车型")' clearable filterable>
        <el-option v-for="model in models" :key="model.id" :value="model.id" :label="model.name" />
      </el-select>
      <template #actions>
        <el-button :loading="busy" @click="load"><AppIcon name="refresh" />{{ tr("刷新库存") }}</el-button>
        <el-button v-if="canSeeLocks" @click="emit('navigate', hasRole(context, 'WEB_AGENT') ? 'agent-locks' : 'inventory-locks')">{{ tr("查看库存锁定") }}</el-button>
        <el-button v-if="canWrite && selected.length" type="primary" :disabled="!hasValidQuantities" @click="begin">
          <AppIcon name="lock" />{{ tr("预锁所选 {count} 个 SKU", { count: selected.length }) }}
        </el-button>
      </template>
    </FilterBar>
    <SectionHeader :title='tr("SKU 库存锁定列表")' :description='tr("按具体规格核对库存与占用")' />
    <DataTable :rows="rows" :loading="busy" :error="error" :empty-title='tr("暂无 SKU 库存")' row-key="skuId">
      <RecordIdColumn prop="skuId" label="SKU ID" />
      <el-table-column prop="skuCode" label="SKU" min-width="130" /><el-table-column prop="productName" :label='tr("商品")' min-width="140" /><el-table-column prop="specification" :label='tr("规格")' min-width="100" /><el-table-column prop="modelName" :label='tr("车型")' min-width="100" />
      <el-table-column prop="physicalQty" :label='tr("在库")' width="75" /><el-table-column prop="quarantinedQty" :label='tr("隔离")' width="75" /><el-table-column prop="temporaryLockedQty" :label='tr("临时锁定")' width="90" /><el-table-column prop="orderReservedQty" :label='tr("订单占用")' width="90" /><el-table-column prop="availableQty" :label='tr("SKU净可用")' width="105" />
      <el-table-column :label='tr("缺口")' width="75"><template #default="{ row }"><span :class="{ shortage: row.shortageQty > 0 }">{{ row.shortageQty }}</span></template></el-table-column>
      <el-table-column prop="legacyModelCommittedQty" :label='tr("历史车型承诺")' width="120" /><el-table-column prop="maxNewLockQty" :label='tr("库存参考余量")' width="110" />
      <el-table-column v-if="canWrite" :label='tr("操作")' width="100" fixed="right"><template #default="{ row }"><el-button link type="primary" :disabled="selected.some(item => item.skuId === row.skuId)" @click="add(row)">{{ tr("加入锁定") }}</el-button></template></el-table-column>
      <template #empty-action><el-button size="small" :disabled="busy" @click="load">{{ tr("刷新数据") }}</el-button></template>
    </DataTable>
    <Pagination :total="total" :page-size="20" v-model:page="page" :disabled="busy" @change="load" />
    </section>
    <AlertCard :title='tr("锁定规则")' :description='tr("SKU预锁数量不受当前库存限制。客户累计收款达到PI约定定金后优先占库；剩余库存不足以满足的其他未付款订单将整单取消并记录原因。预锁不保证供货，已收款和已出库订单不自动取消。")' />
    <section v-if="canWrite && selected.length" class="selection">
      <h3>{{ trInline("待锁定商品（") }}{{ selected.length }} {{ trInline("个 SKU）") }}</h3>
      <div v-for="item in selected" :key="item.skuId" class="selected-row">
        <strong>{{ item.skuCode }}</strong>
        <el-input-number v-model="item.quantity" :aria-label="item.skuCode + ' ' + tr('预锁数量')" :min="1" :max="maxLockQuantity" :precision="0" />
        <el-button link type="danger" @click="selected = selected.filter(row => row.skuId !== item.skuId)">{{ tr("移除") }}</el-button>
      </div>
      <p v-if="!hasValidQuantities" class="note">{{ tr("每个 SKU 的预锁数量须为 1 至 1000000 的整数。") }}</p>
      <el-button type="primary" :disabled="!hasValidQuantities" @click="begin">{{ tr("选择客户并预锁") }}</el-button>
    </section>
    <el-button v-if="result.length" class="reopen-result" @click="showResult">{{ tr("查看本次预锁结果") }}</el-button>
    <el-dialog v-model="visible" :title="dialogStep === 'result' ? resultTitle : tr('确认 SKU 预锁')"
      :width="dialogStep === 'result' ? 'min(620px, calc(100vw - 32px))' : 'min(560px, calc(100vw - 32px))'"
      :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
      <div v-if="dialogStep === 'result'" class="lock-result">
        <table class="lock-result-table">
          <thead>
            <tr><th scope="col">SKU</th><th scope="col">{{ tr("锁定单号") }}</th><th scope="col">{{ tr("数量（件）") }}</th></tr>
          </thead>
          <tbody>
            <tr v-for="lock in result" :key="lock.id">
              <td>{{ lock.skuCode }}</td>
              <td>
                {{ lock.lockNo }}
              </td>
              <td>{{ lock.lockQty }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <template v-else>
      <p class="lock-preview" aria-live="polite">{{ tr("本次预锁 {count} 个 SKU，共 {quantity} 台。转单时整批生成一个订单号。", { count: selected.length, quantity: selectedQuantity }) }}</p>
      <div class="lock-quantities">
        <table class="lock-quantity-table">
          <thead>
            <tr><th scope="col">SKU</th><th scope="col">{{ tr("锁定数量") }}</th></tr>
          </thead>
          <tbody>
            <tr v-for="item in selected" :key="item.skuId">
              <th scope="row">{{ item.skuCode }}</th>
              <td>
                <el-input-number v-model="item.quantity" :aria-label="item.skuCode + ' ' + tr('锁定数量')"
                  :min="1" :max="maxLockQuantity" :precision="0" :value-on-clear="null" :disabled="saving" />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-if="!hasValidQuantities" class="note" role="status">{{ tr("每个 SKU 的预锁数量须为 1 至 1000000 的整数。") }}</p>
      <el-alert v-if="saveError" type="error" :title="tr(saveError)" :closable="false" />
      <el-form label-width="100px" :disabled="saving">
        <el-form-item :label='tr("订单客户")' required>
          <el-select v-model="form.customerId" filterable :placeholder='tr("选择锁定所属客户")'><el-option v-for="item in customers" :key="item.id" :label="`${item.name}（${item.code}）`" :value="item.id" /></el-select>
          <el-button v-if="canCreateMasterData" class="quick-create-link" link type="primary" @click="navigateToMasterData('customers')">{{ tr("没有需要的客户？在这里新增客户") }}</el-button>
        </el-form-item>
        <el-form-item :label='tr("所属客户")'>
          <el-select v-model="form.agentId" clearable filterable :placeholder='tr("不选表示直接客户")'><el-option v-for="item in agents" :key="item.id" :label="`${item.name}（${item.code}）`" :value="item.id" /></el-select>
          <el-button v-if="canCreateMasterData" class="quick-create-link" link type="primary" @click="navigateToMasterData('agents')">{{ tr("没有需要的客户？在这里新增客户") }}</el-button>
        </el-form-item>
        <el-form-item :label='tr("截止时间")'><el-date-picker v-model="form.expiresAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" :placeholder='tr("留空采用系统默认时长")' /></el-form-item>
        <el-form-item :label='tr("备注")'><el-input v-model="form.remark" type="textarea" maxlength="500" /></el-form-item>
      </el-form>
      <p class="note">{{ tr("预锁允许超过当前库存。达到 PI 约定定金后优先占库，剩余库存不足的其他未付款订单将整单取消并记录原因。转单客户须与锁定客户一致。") }}</p>
      </template>
      <template #footer>
        <div v-if="dialogStep === 'result'" class="lock-result-footer">
          <el-button v-if="canConvert && result.length" type="primary"
            @click="visible = false; emit('convert', result[0].lockNo)">
            {{ tr("整批转订单 / PI") }}
          </el-button>
          <el-button v-if="canSeeLocks" @click="emit('navigate', 'inventory-locks')">{{ tr("查看全部锁定与关联订单") }}</el-button>
          <el-button ref="doneButton" type="primary" @click="visible = false">{{ tr("关闭") }}</el-button>
        </div>
        <template v-else>
          <el-button :disabled="saving" @click="visible = false">{{ tr("返回") }}</el-button>
          <el-button type="primary" :loading="saving" :disabled="!hasValidQuantities" @click="save">{{ tr("确认预锁") }}</el-button>
        </template>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.sku-workspace{padding:24px;background:#fff;border:1px solid #e3e9ef;border-radius:12px}.sku-workspace header,.toolbar,.selected-row{display:flex;align-items:center;justify-content:space-between;gap:16px}.sku-workspace h2{margin:0;color:#203446}.sku-workspace header p,.note{color:#6d7d89;font-size:13px;line-height:1.7}.toolbar{justify-content:flex-start;margin:20px 0 12px}.el-pagination{margin-top:18px}.selection{margin-top:22px;padding:18px;background:#f6f9fc;border-radius:8px}.selected-row{justify-content:flex-start;margin-bottom:12px}.selected-row strong{min-width:160px}.shortage{font-weight:700;color:#c1392b}.el-select{width:100%}.selection>.el-button{margin-top:12px}
.quick-create-link{height:auto;margin-top:4px;padding:0;font-size:12px}
.lock-preview { margin: 0 0 16px; font-weight: 600; color: var(--el-text-color-primary); }
.lock-quantities { max-height: min(240px, 36vh); overflow: auto; margin-bottom: 16px; }
.lock-quantity-table { width: 100%; border-collapse: collapse; text-align: start; }
.lock-quantity-table th, .lock-quantity-table td { padding: 8px; border-bottom: 1px solid var(--el-border-color-lighter); text-align: start; }
.lock-quantity-table thead th { position: sticky; top: 0; z-index: 1; background: var(--el-fill-color-light); }
.lock-quantity-table tbody th { font-weight: normal; overflow-wrap: anywhere; }
.lock-quantity-table td { width: 140px; }
.lock-quantity-table .el-input-number { width: 100%; }
.lock-result { max-height: min(360px, 50vh); overflow: auto; }
.lock-result-table { width: 100%; border-collapse: collapse; text-align: start; }
.lock-result-table th, .lock-result-table td { padding: 10px 8px; border-bottom: 1px solid var(--el-border-color-lighter); overflow-wrap: anywhere; }
.lock-result-table th { background: var(--el-fill-color-light); text-align: start; }
.lock-result-table th:last-child, .lock-result-table td:last-child { text-align: end; }
.lock-result-footer { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.lock-result-footer .el-button { margin: 0; }
.reopen-result { align-self: flex-start; }
</style>
