<script setup lang="ts">
import RecordIdColumn from './table/RecordIdColumn.vue'
import { isSelectablePiLayout } from '../pi-template-layouts'
import { onMounted, reactive, ref, toRaw } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { fulfillmentApi, type FulfillmentProfile, type FulfillmentProfileDraft } from '../fulfillment-api'
import { piApi, type PiTemplate } from '../pi-api'
const props = defineProps<{ canManage: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const rows = ref<FulfillmentProfile[]>([]), busy = ref(false), error = ref(''), editing = ref(false)
const templates = ref<PiTemplate[]>([]), warehouses = ref<{ id: number; name: string }[]>([])
const empty = (): FulfillmentProfileDraft => ({ profileCode: '', version: '', name: '', tradeType: 'EXPORT', collectionEntity: '', templateCode: '', templateVersion: '', reason: '', rules: { paymentMode: 'PREPAY', deliveryMode: 'SELLER_TRANSPORT', viaForwarderWarehouse: false, salesContractRequired: true, purchaseContractRequired: false, dispatchSheetRequired: true, preparationRequired: true, settlementRequired: true, requiredDocuments: [], warehouseIds: [] } })
const draft = reactive(empty())
const flags = [['salesContractRequired', '要求销售合同'], ['purchaseContractRequired', '要求购销合同'], ['dispatchSheetRequired', '要求同步出货表'], ['preparationRequired', '要求确认备货'], ['settlementRequired', '要求款项结清后结案']] as const
const message = (e: unknown) => api.isAxiosError(e) ? e.response?.data?.message || '操作失败，请重试' : e instanceof Error ? e.message : '操作失败，请重试'
async function load() { busy.value = true; error.value = ''; try { rows.value = await fulfillmentApi.list() } catch(e) { error.value = message(e) } finally { busy.value = false } }
async function begin(source?: FulfillmentProfile) {
  Object.assign(draft, source ? { ...structuredClone(toRaw(source)), version: '', reason: '' } : empty()); editing.value = true
  busy.value = true; error.value = ''
  try {
    const options = await piApi.options(); warehouses.value = options.warehouses || []
    const all: PiTemplate[] = []; let page = 1
    while (true) { const result = await piApi.templates(undefined, page++); all.push(...result.records); if (all.length >= result.total || !result.records.length) break }
    templates.value = all.filter(t => isSelectablePiLayout(t.layoutCode))
  } catch(e) { error.value = message(e) } finally { busy.value = false }
}
function selectTemplate(identity: string) {
  const t = templates.value.find(t => `${t.templateCode}@${t.templateVersion}` === identity)
  if(t) { draft.templateCode=t.templateCode; draft.templateVersion=t.templateVersion; draft.tradeType=t.tradeType; draft.collectionEntity=t.beneficiary }
}
async function publish() {
  busy.value = true; error.value = ''
  try { await fulfillmentApi.publish(draft); editing.value=false; await load(); emit('changed'); ElMessage.success('配置版本已发布') } catch(e) { error.value=message(e) } finally { busy.value=false }
}
async function availability(row: FulfillmentProfile) {
  try {
    const { value } = await ElMessageBox.prompt('停用仅限制新单选用，历史单据继续使用该版本。请填写原因。', row.enabled ? '停用配置' : '启用配置', { inputValidator: v => !!v?.trim() || '请填写原因' })
    await fulfillmentApi.availability(row.id, !row.enabled, value); await load(); emit('changed')
  } catch(e) { if(e!=='cancel' && e!=='close') error.value=message(e) }
}
onMounted(load)
</script>
<template>
  <section v-loading="busy" class="fulfillment-profiles">
    <p>按业务约定配置，不按国家另建流程。发布后版本不可覆盖；新版本不会改变已确认订单。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-button v-if="canManage" type="primary" @click="begin()">发布新配置</el-button><el-button @click="load">刷新</el-button>
    <el-table :data="rows" empty-text="尚无配置，可先发布 PI 模板，再建立履约配置">
      <RecordIdColumn />
      <el-table-column prop="name" label="配置名称" min-width="160" /><el-table-column prop="version" label="版本" width="85" />
      <el-table-column label="付款 / 交付" min-width="200"><template #default="{row}">{{ row.rules.paymentMode === 'CREDIT' ? '后付款（须授权）' : '预付' }} · {{ row.rules.deliveryMode === 'CUSTOMER_PICKUP' ? '客户货代提货' : '我方安排运输' }}</template></el-table-column>
      <el-table-column prop="collectionEntity" label="收款主体" min-width="150" />
      <el-table-column label="状态" width="80"><template #default="{row}">{{ row.enabled ? '启用' : '停用' }}</template></el-table-column>
      <el-table-column type="expand"><template #default="{row}"><p>模板：{{ row.templateCode }} · {{ row.templateVersion }}；适用仓库：{{ row.rules.warehouseIds.length ? row.rules.warehouseIds.join('、') : '全部已启用仓库' }}；所需单据：{{ row.rules.requiredDocuments.join('、') || '无额外单据' }}</p><p>{{ flags.filter(([key]) => row.rules[key]).map(([,label]) => label).join('；') }}{{ row.rules.viaForwarderWarehouse ? '；需送货代仓' : '' }}</p></template></el-table-column>
      <el-table-column v-if="canManage" label="操作" width="170"><template #default="{row}"><el-button link @click="begin(row)">复制新版本</el-button><el-button link @click="availability(row)">{{ row.enabled ? '停用' : '启用' }}</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="editing" title="发布履约配置版本" width="min(780px,95vw)" append-to-body :close-on-click-modal="false">
      <el-form label-position="top" class="profile-form">
        <el-form-item label="配置编码" required><el-input v-model="draft.profileCode" placeholder="如 STANDARD_DELIVERY（大写英文）" maxlength="64" /></el-form-item>
        <el-form-item label="版本" required><el-input v-model="draft.version" placeholder="如 1.0" maxlength="32" /></el-form-item>
        <el-form-item label="配置名称" required><el-input v-model="draft.name" placeholder="如 后付款客户提货" maxlength="120" /></el-form-item>
        <el-form-item label="PI 模板 / 卖方" required><el-select :model-value="draft.templateCode ? `${draft.templateCode}@${draft.templateVersion}` : ''" filterable @change="selectTemplate"><el-option v-for="t in templates" :key="`${t.templateCode}@${t.templateVersion}`" :value="`${t.templateCode}@${t.templateVersion}`" :label="`${t.templateCode} · ${t.templateVersion} · ${t.sellerName}`" /></el-select></el-form-item>
        <el-form-item label="收款主体" required><el-input v-model="draft.collectionEntity" maxlength="200" placeholder="真实收款公司名称或主体编码" /></el-form-item>
        <el-form-item label="适用仓库（留空表示全部启用仓库）"><el-select v-model="draft.rules.warehouseIds" multiple filterable><el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" /></el-select></el-form-item>
        <el-form-item label="付款方式" required><el-select v-model="draft.rules.paymentMode" @change="draft.rules.paymentMode === 'CREDIT' && (draft.rules.settlementRequired = true)"><el-option value="PREPAY" label="预付：按单据约定金额锁库和放行" /><el-option value="CREDIT" label="后付款：逐单授权保留库存及放行" /></el-select></el-form-item>
        <el-form-item label="交付方式" required><el-select v-model="draft.rules.deliveryMode" @change="draft.rules.deliveryMode === 'CUSTOMER_PICKUP' && (draft.rules.viaForwarderWarehouse = false)"><el-option value="SELLER_TRANSPORT" label="我方安排运输（货代或快递）" /><el-option value="CUSTOMER_PICKUP" label="客户货代到仓提货" /></el-select></el-form-item>
        <el-checkbox v-model="draft.rules.viaForwarderWarehouse" :disabled="draft.rules.deliveryMode === 'CUSTOMER_PICKUP'">我方需先送至货代仓并确认到仓</el-checkbox>
        <el-checkbox v-for="[key,label] in flags" :key="key" v-model="draft.rules[key]" :disabled="key === 'settlementRequired' && draft.rules.paymentMode === 'CREDIT'">{{ label }}</el-checkbox>
        <el-form-item label="必需业务单据"><el-checkbox-group v-model="draft.rules.requiredDocuments"><el-checkbox value="CI">CI 发送</el-checkbox><el-checkbox value="RD">RD 发送及回签</el-checkbox><el-checkbox value="CMR">CMR 回收</el-checkbox></el-checkbox-group></el-form-item>
        <el-form-item label="发布原因" required><el-input v-model="draft.reason" maxlength="500" type="textarea" /></el-form-item>
      </el-form>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <template #footer><el-button @click="editing=false">取消</el-button><el-button type="primary" :loading="busy" @click="publish">发布不可覆盖的版本</el-button></template>
    </el-dialog>
  </section>
</template>
<style scoped>.profile-form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px 20px}.profile-form :deep(.el-select){width:100%}@media(max-width:600px){.profile-form{grid-template-columns:minmax(0,1fr)}}</style>
