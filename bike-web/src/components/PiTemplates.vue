<script setup lang="ts">
import { language, tr, trInline } from '../i18n/language'
import RecordIdColumn from './table/RecordIdColumn.vue'
import { isSelectablePiLayout, piLayoutLabel, piTemplateLayouts } from '../pi-template-layouts'
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { piApi, type PiTemplate, type PiTemplatePublish } from '../pi-api'
const props = defineProps<{ tradeType?: string; canManage: boolean; selectable?: boolean; initialTemplate?: PiTemplate }>()
const emit = defineEmits<{ select: [value: PiTemplate] }>()
const rows = ref<PiTemplate[]>([]), total = ref(0), page = ref(1), busy = ref(false), error = ref(''), editing = ref(false)
const sourceTemplate = ref<PiTemplate | null>(null), saving = ref(false), formError = ref('')
const fields = [['templateCode', '模板编码'], ['templateVersion', '版本号'], ['sellerName', '卖方名称'], ['sellerAddress', '卖方地址'], ['sellerCountry', '卖方国家/地区'], ['sellerContactName', '卖方联系人'], ['sellerPhone', '卖方电话'], ['sellerEmail', '卖方Email'], ['bankName', '收款银行'], ['bankAccount', '收款账号'], ['beneficiary', '收款单位'], ['reason', '发布原因']] as const
type TemplateField = typeof fields[number][0]
// 选填：外销 / 欧洲单据按原件显示的银行附加资料和卖方登记号、VAT，未填写的栏目在单据中留空。
const optionalFields = [['bankAddress', '银行地址 Bank Address', 500], ['swiftCode', 'SWIFT Code', 64], ['bankCode', 'Bank Code', 64], ['branchCode', 'Branch Code', 64], ['sellerRegistrationNo', '卖方登记号（如 KVK-No.）', 100], ['sellerVatNo', '卖方 VAT 号', 100]] as const
type OptionalField = typeof optionalFields[number][0]
const templateForm = ref<{ $el: HTMLElement }>()
const fieldErrors = reactive<Partial<Record<TemplateField, string>>>({})
const invalidFields = computed(() => fields.filter(([key]) => fieldErrors[key]))
function fieldIssue(key: TemplateField): string {
  const label = fields.find(([field]) => field === key)![1]
  const value = draft[key]
  if (!value.trim()) return `请填写${label}`
  if (key === 'templateCode' && !/^[A-Z0-9][A-Z0-9_-]{0,63}$/.test(value))
    return '模板编码：须以大写字母或数字开头，仅支持大写字母、数字、下划线和短横线，最多64位，例如 EXPORT_01'
  if (key === 'templateVersion' && !/^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$/.test(value))
    return '版本号：须以字母或数字开头，仅支持字母、数字、点、下划线和短横线，最多32位，例如 V1.0'
  if (key === 'templateVersion' && sourceTemplate.value?.templateVersion === value)
    return '版本号：不能与原版本相同，请填写新的版本号'
  if (key === 'sellerEmail' && !/^[^\s@]+@[^\s@]+$/.test(value))
    return '卖方Email：请填写有效的邮箱地址，例如 sales@example.com'
  return ''
}
function fieldErrorText(key: TemplateField, label: string) {
  const error = fieldErrors[key]
  if (error === '请填写' + label) return tr('请填写{field}', { field: tr(label) })
  return tr(error)
}
function validateField(key: TemplateField) {
  if (fieldErrors[key]) formError.value = ''
  const issue = fieldIssue(key)
  if (issue) fieldErrors[key] = issue
  else delete fieldErrors[key]
}
async function focusField(key: TemplateField) {
  await nextTick()
  const field = templateForm.value?.$el.querySelector<HTMLElement>(`[data-template-field="${key}"]`)
  field?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  field?.querySelector<HTMLInputElement | HTMLTextAreaElement>('input:not(:disabled), textarea:not(:disabled)')?.focus({ preventScroll: true })
}
const emptyDraft = (): PiTemplatePublish => ({ templateCode: '', templateVersion: '', tradeType: props.tradeType || 'DOMESTIC', layoutCode: 'STANDARD_V3', sellerName: '', sellerAddress: '', sellerContact: '', sellerCountry: '', sellerContactName: '', sellerPhone: '', sellerEmail: '', bankName: '', bankAccount: '', beneficiary: '', reason: '', bankAddress: '', swiftCode: '', bankCode: '', branchCode: '', sellerRegistrationNo: '', sellerVatNo: '' })
const draft = reactive<PiTemplatePublish>(emptyDraft())
const layoutOptions = computed(() => sourceTemplate.value && isSelectablePiLayout(sourceTemplate.value.layoutCode)
  && !piTemplateLayouts.some(layout => layout.value === sourceTemplate.value?.layoutCode)
  ? [{ value: sourceTemplate.value.layoutCode, label: piLayoutLabel(sourceTemplate.value.layoutCode) }, ...piTemplateLayouts] : piTemplateLayouts)
function beginAdjustment(source?: PiTemplate) {
  if (!props.canManage || saving.value) return
  sourceTemplate.value = source || null
  Object.assign(draft, emptyDraft())
  if (source) {
    // 只复制发布字段，旧版本和已生成单据继续保留原始资料。
    for (const [key] of fields) if (key !== 'reason') draft[key] = source[key] || ''
    draft.sellerContact = source.sellerContact || ''
    for (const [key] of optionalFields) draft[key] = source[key] || ''
    draft.tradeType = source.tradeType
    draft.layoutCode = isSelectablePiLayout(source.layoutCode) ? source.layoutCode : 'STANDARD_V3'
    draft.templateVersion = `R${Date.now()}`
  }
  for (const [key] of fields) delete fieldErrors[key]
  formError.value = ''; editing.value = true
}
let version = 0
watch(() => props.initialTemplate, source => { if (source) beginAdjustment(source) }, { immediate: true })
const formatDateTime = (value?: string | null) => value ? value.replace('T', ' ').slice(0, 19) : '—'
async function load() {
  const current = ++version; busy.value = true; error.value = ''
  try { const data = await piApi.templates(props.tradeType, page.value); if (current === version) { rows.value = data.records; total.value = data.total } }
  catch (e) { if (current === version) error.value = api.isAxiosError(e) ? e.response?.data?.message || '模板加载失败' : '模板加载失败' }
  finally { if (current === version) busy.value = false }
}
watch(() => props.tradeType, () => { page.value = 1; void load() }, { immediate: true })
async function publish() {
  if (!props.canManage || saving.value) return
  formError.value = ''
  for (const [key] of fields) validateField(key)
  if (invalidFields.value.length) { await focusField(invalidFields.value[0]![0]); return }
  saving.value = true; formError.value = ''
  try {
    await ElMessageBox.confirm(tr('保存后生成可选用的新版本，历史版本和已生成单据保留原资料。请核对卖方、银行信息和版式。'), tr('保存模板'), { type: 'warning' })
    const result = await piApi.publishTemplate({ ...draft })
    editing.value = false; page.value = 1; await load(); ElMessage.success(tr('模板已保存为新版本'))
    if (props.selectable && result.tradeType === props.tradeType) emit('select', result)
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      const response = api.isAxiosError(e) ? e.response?.data : undefined
      formError.value = response?.message || '模板保存失败，请重试'
      const field = response?.code === 'PI_TEMPLATE_EXISTS' ? 'templateVersion'
        : fields.find(([, label]) => formError.value.includes(label))?.[0]
      if (field) {
        fieldErrors[field] = response?.code === 'PI_TEMPLATE_EXISTS' ? '版本号：此模板版本已发布，请使用新的版本号' : formError.value
        saving.value = false
        await focusField(field)
      }
    }
  }
  finally { saving.value = false }
}
</script>
<template>
  <section v-loading="busy">
    <p>{{ tr("点击“调整模板”可修改卖方资料、默认收款银行和 PI 版式，保存为新版本。内销和外销均可选择标准明细表或双栏发票，历史单据保留原资料。") }}</p>
    <el-alert v-if="error" :title="tr(error)" type="error" :closable="false" />
    <el-button @click="load">{{ tr("刷新模板") }}</el-button><el-button v-if="canManage" type="primary" @click="beginAdjustment()">{{ tr("新建模板") }}</el-button>
    <el-table :data="rows" :empty-text='tr("暂无已发布模板，请联系模板管理员")'>
      <RecordIdColumn />
      <el-table-column prop="templateCode" :label='tr("模板")' /><el-table-column prop="templateVersion" :label='tr("版本")' width="80" />
      <el-table-column :label='tr("类型")' width="80"><template #default="s">{{ s.row.tradeType === 'EXPORT' ? tr('外销') : tr('内销') }}</template></el-table-column>
      <el-table-column :label='tr("版式")' width="170"><template #default="s">{{ tr(piLayoutLabel(s.row.layoutCode)) }}</template></el-table-column>
      <el-table-column prop="sellerName" :label='tr("卖方")' /><el-table-column prop="bankName" :label='tr("银行")' /><el-table-column prop="bankAccount" :label='tr("账号")' />
      <el-table-column :label='tr("创建时间")' min-width="175"><template #default="s">{{ formatDateTime(s.row.publishedAt) }}</template></el-table-column>
      <el-table-column :label='tr("修改时间")' min-width="175"><template #default="s">{{ formatDateTime(s.row.publishedAt) }}</template></el-table-column>
      <el-table-column type="expand"><template #default="s"><el-descriptions :column="2" border><el-descriptions-item v-for="key in ['sellerAddress', 'sellerCountry', 'sellerContactName', 'sellerPhone', 'sellerEmail']" :key="key" :label="fields.find(f => f[0] === key)?.[1]">{{ s.row[key] || tr('历史未记录') }}</el-descriptions-item><el-descriptions-item v-for="[key, label] in optionalFields" :key="key" :label="tr(label)">{{ s.row[key] || tr('未填写') }}</el-descriptions-item></el-descriptions></template></el-table-column>
      <el-table-column v-if="canManage || selectable" :label='tr("操作")' :width="canManage && selectable ? 160 : 110" fixed="right"><template #default="s"><el-button v-if="canManage" link type="primary" @click="beginAdjustment(s.row)">{{ tr("调整模板") }}</el-button><el-button v-if="selectable" link type="primary" :disabled="!isSelectablePiLayout(s.row.layoutCode)" @click="emit('select', s.row)">{{ tr("选用") }}</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next, total" @current-change="load" />
    <el-dialog v-model="editing" :title="sourceTemplate ? tr('调整模板') : tr('新建模板')" width="min(780px, 94vw)" append-to-body :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
      <p v-if="sourceTemplate">{{ trInline("正在调整") }} {{ sourceTemplate.templateCode }} · {{ sourceTemplate.templateVersion }}{{ trInline("，已带入原资料，并生成可修改的新版本号。") }}</p>
      <el-alert v-if="invalidFields.length" :title="tr('有 {value0} 处填写错误，请修改标红字段', { value0: invalidFields.length })" type="error" show-icon :closable="false">
        <div v-for="[key, label] in invalidFields" :key="key"><el-button link type="danger" class="template-error-link" @click="focusField(key)">{{ fieldErrorText(key, label) }}</el-button></div>
      </el-alert>
      <el-alert v-else-if="formError" :title="tr(formError)" type="error" show-icon :closable="false" />
      <el-form v-if="canManage" ref="templateForm" label-position="top" class="template-form" :disabled="saving" @submit.prevent="publish">
        <el-form-item :label='tr("贸易类型")'><el-select v-model="draft.tradeType" :aria-label='tr("贸易类型")' :disabled="Boolean(sourceTemplate) || Boolean(tradeType)"><el-option value="DOMESTIC" :label='tr("内销")' /><el-option value="EXPORT" :label='tr("外销")' /></el-select></el-form-item>
        <el-form-item :label='tr("PI 版式")' required><el-select v-model="draft.layoutCode" :aria-label='tr("PI 版式")'><el-option v-for="layout in layoutOptions" :key="layout.value" :value="layout.value" :label="tr(layout.label)" /></el-select></el-form-item>
        <el-form-item v-for="[key, label] in fields" :key="key" :label="tr(label)" required :error="fieldErrorText(key, label)" :data-template-field="key"><el-input v-model="draft[key]" :aria-label="tr(label)" :aria-invalid="Boolean(fieldErrors[key])" :disabled="key === 'templateCode' && Boolean(sourceTemplate)" :placeholder="key === 'templateCode' ? tr('例如 EXPORT_01（大写字母、数字、_、-）') : key === 'templateVersion' ? tr('例如 V1.0') : undefined" :type="['sellerAddress', 'reason'].includes(key) ? 'textarea' : key === 'sellerEmail' ? 'email' : 'text'" :maxlength="key === 'sellerAddress' ? 1000 : key === 'reason' ? 500 : key === 'sellerEmail' ? 254 : ['sellerCountry', 'sellerContactName'].includes(key) ? 100 : key === 'sellerPhone' || key === 'templateCode' ? 64 : key === 'templateVersion' ? 32 : 200" @blur="validateField(key)" @input="fieldErrors[key] && validateField(key)" /></el-form-item>
        <el-form-item :label='tr("补充联系方式（可选）")'><el-input v-model="draft.sellerContact" :aria-label='tr("补充联系方式")' maxlength="300" /></el-form-item>
        <el-form-item v-for="[key, label, max] in optionalFields" :key="key" :label="tr('{value0}（选填）', { value0: label })" :data-template-field="key"><el-input v-model="draft[key as OptionalField]" :aria-label="tr(label)" :maxlength="max" :type="key === 'bankAddress' ? 'textarea' : 'text'" /></el-form-item>
      </el-form>
      <template #footer><div v-if="invalidFields.length" class="template-error-summary" role="alert">{{ trInline("请修改：") }}{{ invalidFields.map(([, label]) => tr(label)).join(language === 'en' ? ', ' : '、') }}。<el-button link type="danger" @click="focusField(invalidFields[0]![0])">{{ tr("定位第一处错误") }}</el-button></div><div v-else-if="formError" class="template-error-summary" role="alert">{{ tr(formError) }}</div><el-button :disabled="saving" @click="editing = false">{{ tr("取消") }}</el-button><el-button v-if="canManage" type="primary" :loading="saving" @click="publish">{{ tr("保存为新版本") }}</el-button></template>
    </el-dialog>
  </section>
</template>
<style scoped>
.template-form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 20px}.template-form :deep(.el-select){width:100%}
.template-form :deep(.el-form-item__error){position:static;line-height:1.5;padding-top:6px}
.template-error-link{white-space:normal;text-align:left;height:auto;line-height:1.5}
.template-error-summary{color:var(--el-color-danger);text-align:left;margin-bottom:12px;line-height:1.5}
@media(max-width:600px){.template-form{grid-template-columns:minmax(0,1fr)}}
</style>
