<script setup lang="ts">
import RecordIdColumn from './table/RecordIdColumn.vue'
import { isSelectablePiLayout } from '../pi-template-layouts'
import { computed, onMounted, reactive, ref, toRaw } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { fulfillmentApi, type FulfillmentProfile, type FulfillmentProfileDraft } from '../fulfillment-api'
import { piApi, type PiTemplate } from '../pi-api'
import PiTemplates from './PiTemplates.vue'
const props = defineProps<{ canManage: boolean; seedProfileId?: number; seedWarehouseId?: number; seedTradeType?: string; autoOpen?: boolean }>()
const emit = defineEmits<{ changed: []; published: [profile: FulfillmentProfile] }>()
const rows = ref<FulfillmentProfile[]>([]), busy = ref(false), error = ref(''), editing = ref(false)
const templates = ref<PiTemplate[]>([]), warehouses = ref<{ id: number; name: string }[]>([])
const empty = (): FulfillmentProfileDraft => ({ profileCode: '', version: '', name: '', tradeType: 'EXPORT', collectionEntity: '', templateCode: '', templateVersion: '', reason: '', rules: { paymentMode: 'PREPAY', deliveryMode: 'SELLER_TRANSPORT', viaForwarderWarehouse: false, salesContractRequired: true, purchaseContractRequired: false, dispatchSheetRequired: true, preparationRequired: true, settlementRequired: true, requiredDocuments: [], warehouseIds: [] } })
const draft = reactive(empty())
const templateManagerVisible = ref(false)
const tradeLabel = computed(() => draft.tradeType === 'EXPORT' ? '外销' : '内销')
const matchingTemplates = computed(() => templates.value.filter(template => template.tradeType === draft.tradeType))
const targetWarehouseName = computed(() => warehouses.value.find(warehouse => warehouse.id === props.seedWarehouseId)?.name || '当前 PI 的出库仓库')
const flags = [['salesContractRequired', '要求销售合同'], ['purchaseContractRequired', '要求购销合同'], ['dispatchSheetRequired', '要求同步出货表'], ['preparationRequired', '要求确认备货'], ['settlementRequired', '要求款项结清后结案']] as const
const message = (e: unknown) => api.isAxiosError(e) ? e.response?.data?.message || '操作失败，请重试' : e instanceof Error ? e.message : '操作失败，请重试'
async function load() { busy.value = true; error.value = ''; try { rows.value = await fulfillmentApi.list() } catch(e) { error.value = message(e) } finally { busy.value = false } }
async function loadReferenceData() {
  warehouses.value = await piApi.warehouseOptions()
  if (!props.canManage) return
  const all: PiTemplate[] = []; let page = 1
  while (true) { const result = await piApi.templates(undefined, page++); all.push(...result.records); if (all.length >= result.total || !result.records.length) break }
  templates.value = all.filter(t => isSelectablePiLayout(t.layoutCode))
}
function fillDraft(source?: FulfillmentProfile) {
  const next = source ? {
    profileCode: source.profileCode, version: '', name: source.name, tradeType: source.tradeType,
    collectionEntity: source.collectionEntity, templateCode: source.templateCode, templateVersion: source.templateVersion,
    reason: '', rules: structuredClone(toRaw(source.rules)),
  } : { ...empty(), tradeType: props.seedTradeType || 'EXPORT' }
  const warehouseId = props.seedWarehouseId
  if (warehouseId && !next.rules.warehouseIds.includes(warehouseId)) next.rules.warehouseIds.push(warehouseId)
  Object.assign(draft, next)
}
async function begin(source?: FulfillmentProfile) {
  if (!props.canManage) return
  busy.value = true; error.value = ''
  try {
    if (!warehouses.value.length || !templates.value.length) await loadReferenceData()
    fillDraft(source); editing.value = true
  } catch(e) { error.value = message(e) } finally { busy.value = false }
}
function selectTemplate(identity: string) {
  const t = matchingTemplates.value.find(t => `${t.templateCode}@${t.templateVersion}` === identity)
  if(t) { draft.templateCode=t.templateCode; draft.templateVersion=t.templateVersion; draft.collectionEntity=t.beneficiary }
}
function changeTradeType() {
  draft.templateCode = ''; draft.templateVersion = ''; draft.collectionEntity = ''; error.value = ''
}
function useManagedTemplate(template: PiTemplate) {
  if (template.tradeType !== draft.tradeType || !isSelectablePiLayout(template.layoutCode)) return
  const index = templates.value.findIndex(item => item.templateCode === template.templateCode && item.templateVersion === template.templateVersion)
  if (index < 0) templates.value.push(template)
  else templates.value[index] = template
  selectTemplate(`${template.templateCode}@${template.templateVersion}`)
  templateManagerVisible.value = false
}
function validationMessage() {
  if (!/^[A-Z0-9][A-Z0-9_-]{0,63}$/.test(draft.profileCode)) return '请填写配置编码，仅支持大写字母、数字、下划线和短横线，例如 EXPORT_PREPAY'
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$/.test(draft.version)) return '请填写版本号，例如 1.0；复制新版本时请使用未发布过的版本号'
  if (rows.value.some(row => row.profileCode === draft.profileCode && row.version === draft.version)) return '此配置版本已发布，请更换版本号，例如从 1.0 改为 1.1'
  if (!draft.name.trim()) return '请填写便于业务员识别的配置名称，例如 外销发出 PI 后付款'
  if (props.seedTradeType && draft.tradeType !== props.seedTradeType) return '配置的贸易类型必须与当前 PI 一致'
  if (!matchingTemplates.value.some(template => template.templateCode === draft.templateCode && template.templateVersion === draft.templateVersion)) return `请选择已发布的${tradeLabel.value} PI 模板；没有模板时，点击“配置${tradeLabel.value}模板与银行资料”`
  if (!draft.collectionEntity.trim()) return '请核对并填写真实收款主体'
  if (props.seedWarehouseId && draft.rules.warehouseIds.length && !draft.rules.warehouseIds.includes(props.seedWarehouseId)) return `适用仓库必须包含${targetWarehouseName.value}，才能用于当前 PI`
  if (!draft.reason.trim()) return '请填写发布原因，例如 为当前仓库新增外销履约配置'
  return ''
}
async function publish() {
  if (!props.canManage || busy.value) return
  error.value = validationMessage()
  if (error.value) return
  busy.value = true; error.value = ''
  try { const published = await fulfillmentApi.publish(draft); editing.value=false; await load(); emit('published', published); ElMessage.success('配置版本已发布') } catch(e) { error.value=message(e) } finally { busy.value=false }
}
async function availability(row: FulfillmentProfile) {
  try {
    const { value } = await ElMessageBox.prompt('停用仅限制新单选用，历史单据继续使用该版本。请填写原因。', row.enabled ? '停用配置' : '启用配置', { inputValidator: v => !!v?.trim() || '请填写原因' })
    await fulfillmentApi.availability(row.id, !row.enabled, value); await load(); emit('changed')
  } catch(e) { if(e!=='cancel' && e!=='close') error.value=message(e) }
}
function warehouseNames(row: FulfillmentProfile) {
  if (!row.rules.warehouseIds.length) return '全部已启用仓库'
  return row.rules.warehouseIds.map(id => warehouses.value.find(item => item.id === id)?.name || `仓库 ${id}`).join('、')
}
onMounted(async () => {
  await load()
  try { await loadReferenceData() } catch(e) { error.value = message(e) }
  if (props.autoOpen && props.canManage) await begin(rows.value.find(row => row.id === props.seedProfileId))
})
</script>
<template>
  <section v-loading="busy" class="fulfillment-profiles">
    <p>履约配置用于约定收款、发货和单据要求。管理员发布一次后，业务员可在相同贸易类型和适用仓库的 PI 中重复选用。调整规则请复制新版本，已确认订单保留原规则。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-button v-if="canManage" type="primary" @click="begin()">发布新配置</el-button><el-button @click="load">刷新</el-button>
    <el-table :data="rows" empty-text="尚无配置，可先发布 PI 模板，再建立履约配置">
      <RecordIdColumn />
      <el-table-column prop="name" label="配置名称" min-width="160" /><el-table-column prop="version" label="版本" width="85" />
      <el-table-column label="贸易类型" width="90"><template #default="{row}">{{ row.tradeType === 'EXPORT' ? '外销' : '内销' }}</template></el-table-column>
      <el-table-column label="适用仓库" min-width="160"><template #default="{row}">{{ warehouseNames(row) }}</template></el-table-column>
      <el-table-column label="付款 / 交付" min-width="260"><template #default="{row}">{{ row.rules.paymentMode === 'CREDIT' ? '客户收到货后付款' : '发出 PI 后付款' }} · {{ row.rules.deliveryMode === 'CUSTOMER_PICKUP' ? '客户货代提货' : '我方安排运输' }}</template></el-table-column>
      <el-table-column prop="collectionEntity" label="收款主体" min-width="150" />
      <el-table-column label="状态" width="80"><template #default="{row}">{{ row.enabled ? '启用' : '停用' }}</template></el-table-column>
      <el-table-column type="expand"><template #default="{row}"><p>模板：{{ row.templateCode }} · {{ row.templateVersion }}；适用仓库：{{ warehouseNames(row) }}；所需单据：{{ row.rules.requiredDocuments.join('、') || '无额外单据' }}</p><p>{{ flags.filter(([key]) => row.rules[key]).map(([,label]) => label).join('；') }}{{ row.rules.viaForwarderWarehouse ? '；需送货代仓' : '' }}</p></template></el-table-column>
      <el-table-column v-if="canManage" label="操作" width="170"><template #default="{row}"><el-button link @click="begin(row)">复制新版本</el-button><el-button link @click="availability(row)">{{ row.enabled ? '停用' : '启用' }}</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="editing" :title="`配置${tradeLabel}收款与发货规则`" width="min(780px,95vw)" append-to-body :close-on-click-modal="false">
      <el-alert class="profile-guide" type="info" show-icon :closable="false" :title="`${tradeLabel}配置只需完成以下三步`">
        <ol><li>选择{{ tradeLabel }} PI 模板，核对卖方、银行资料和收款主体。</li><li>选择适用仓库，按实际约定填写付款、运输及单据要求。</li><li>填写配置编码、名称、版本和发布原因，核对后发布。</li></ol>
        <p v-if="seedWarehouseId">已带入仓库：{{ targetWarehouseName }}。发布匹配的配置后，会自动选入当前 PI 草稿。</p>
      </el-alert>
      <el-form label-position="top" class="profile-form" :disabled="busy" @submit.prevent="publish">
        <el-form-item label="贸易类型" required><el-select v-model="draft.tradeType" aria-label="配置贸易类型" :disabled="Boolean(seedTradeType)" @change="changeTradeType"><el-option value="EXPORT" label="外销" /><el-option value="DOMESTIC" label="内销" /></el-select><small v-if="seedTradeType" class="profile-help">与当前 PI 保持一致。</small></el-form-item>
        <el-form-item :label="`${tradeLabel} PI 模板 / 卖方`" required><el-select :model-value="draft.templateCode ? `${draft.templateCode}@${draft.templateVersion}` : ''" aria-label="配置 PI 模板" filterable :placeholder="`请选择已发布的${tradeLabel}模板`" @change="selectTemplate"><el-option v-for="t in matchingTemplates" :key="`${t.templateCode}@${t.templateVersion}`" :value="`${t.templateCode}@${t.templateVersion}`" :label="`${t.templateCode} · ${t.templateVersion} · ${t.sellerName}`" /></el-select><small v-if="!matchingTemplates.length" class="profile-help">尚无可用的{{ tradeLabel }}模板，请先补齐真实卖方和银行资料并发布模板。</small><el-button link type="primary" @click="templateManagerVisible = true">配置{{ tradeLabel }}模板与银行资料</el-button></el-form-item>
        <el-form-item label="配置编码" required><el-input v-model="draft.profileCode" placeholder="如 STANDARD_DELIVERY（大写英文）" maxlength="64" /></el-form-item>
        <el-form-item label="版本" required><el-input v-model="draft.version" placeholder="如 1.0" maxlength="32" /></el-form-item>
        <el-form-item label="配置名称" required><el-input v-model="draft.name" placeholder="如 客户收到货后付款并提货" maxlength="120" /></el-form-item>
        <el-form-item label="收款主体" required><el-input v-model="draft.collectionEntity" maxlength="200" placeholder="真实收款公司名称或主体编码" /></el-form-item>
        <el-form-item label="适用仓库（留空表示全部启用仓库）"><el-select v-model="draft.rules.warehouseIds" multiple filterable><el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" /></el-select></el-form-item>
        <el-form-item label="付款方式" required><el-select v-model="draft.rules.paymentMode" @change="draft.rules.paymentMode === 'CREDIT' && (draft.rules.settlementRequired = true)"><el-option value="PREPAY" label="发出 PI 后付款：到账后保留库存并放行" /><el-option value="CREDIT" label="客户收到货后付款：授权后先发货，到账后结案" /></el-select><small class="profile-help">这里只选择流程控制方式，不固定第几天付款。每张 PI 的具体付款时间和条件在“付款条款”中按实际约定填写；收到货后付款仍须逐单授权。</small></el-form-item>
        <el-form-item label="交付方式" required><el-select v-model="draft.rules.deliveryMode" @change="draft.rules.deliveryMode === 'CUSTOMER_PICKUP' && (draft.rules.viaForwarderWarehouse = false)"><el-option value="SELLER_TRANSPORT" label="我方安排运输（货代或快递）" /><el-option value="CUSTOMER_PICKUP" label="客户货代到仓提货" /></el-select></el-form-item>
        <el-checkbox v-model="draft.rules.viaForwarderWarehouse" :disabled="draft.rules.deliveryMode === 'CUSTOMER_PICKUP'">我方需先送至货代仓并确认到仓</el-checkbox>
        <el-checkbox v-for="[key,label] in flags" :key="key" v-model="draft.rules[key]" :disabled="key === 'settlementRequired' && draft.rules.paymentMode === 'CREDIT'">{{ label }}</el-checkbox>
        <p class="profile-help profile-wide">以上勾选项会成为后续办理要求，请逐项核对。销售合同、购销合同和送货代仓是否需要，按实际业务约定选择。</p>
        <el-form-item label="必需业务单据"><el-checkbox-group v-model="draft.rules.requiredDocuments"><el-checkbox value="CI">CI 发送</el-checkbox><el-checkbox value="RD">RD 发送及回签</el-checkbox><el-checkbox value="CMR">CMR 回收</el-checkbox></el-checkbox-group><small class="profile-help">仅勾选本业务确实需要的单据；勾选后须完成对应文件办理，才能结案。</small></el-form-item>
        <el-form-item label="发布原因" required><el-input v-model="draft.reason" maxlength="500" type="textarea" :placeholder="`例如：为${targetWarehouseName}新增${tradeLabel}履约配置`" /></el-form-item>
      </el-form>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <template #footer><el-button @click="editing=false">取消</el-button><el-button type="primary" :loading="busy" @click="publish">发布不可覆盖的版本</el-button></template>
    </el-dialog>
    <el-dialog v-model="templateManagerVisible" :title="`${tradeLabel}模板与银行资料`" width="min(1100px,96vw)" append-to-body destroy-on-close><PiTemplates v-if="templateManagerVisible" :trade-type="draft.tradeType" :can-manage="canManage" selectable @select="useManagedTemplate" /></el-dialog>
  </section>
</template>
<style scoped>.profile-form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px 20px}.profile-form :deep(.el-select){width:100%}.profile-form :deep(.el-form-item){min-width:0}.profile-help{display:block;width:100%;color:var(--el-text-color-secondary);font-size:12px;line-height:1.7;margin:6px 0 0}.profile-wide{grid-column:1 / -1}.profile-guide{margin-bottom:20px}.profile-guide ol{padding-left:20px;margin:8px 0;line-height:1.8}.profile-guide p{margin:8px 0}.profile-form :deep(.el-checkbox){height:auto;white-space:normal}.profile-form :deep(.el-checkbox__label){white-space:normal;line-height:1.7}@media(max-width:600px){.profile-form{grid-template-columns:minmax(0,1fr)}}</style>
