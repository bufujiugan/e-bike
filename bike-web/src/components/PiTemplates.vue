<script setup lang="ts">
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
const emptyDraft = (): PiTemplatePublish => ({ templateCode: '', templateVersion: '', tradeType: props.tradeType || 'DOMESTIC', layoutCode: 'STANDARD_V3', sellerName: '', sellerAddress: '', sellerContact: '', sellerCountry: '', sellerContactName: '', sellerPhone: '', sellerEmail: '', bankName: '', bankAccount: '', beneficiary: '', reason: '' })
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
    await ElMessageBox.confirm('保存后生成可选用的新版本，历史版本和已生成单据保留原资料。请核对卖方、银行信息和版式。', '保存模板', { type: 'warning' })
    const result = await piApi.publishTemplate({ ...draft })
    editing.value = false; page.value = 1; await load(); ElMessage.success('模板已保存为新版本')
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
    <p>点击“调整模板”可修改卖方资料、默认收款银行和 PI 版式，保存为新版本。内销和外销均可选择标准明细表或双栏发票，历史单据保留原资料。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-button @click="load">刷新模板</el-button><el-button v-if="canManage" type="primary" @click="beginAdjustment()">新建模板</el-button>
    <el-table :data="rows" empty-text="暂无已发布模板，请联系模板管理员">
      <RecordIdColumn />
      <el-table-column prop="templateCode" label="模板" /><el-table-column prop="templateVersion" label="版本" width="80" />
      <el-table-column label="类型" width="80"><template #default="s">{{ s.row.tradeType === 'EXPORT' ? '外销' : '内销' }}</template></el-table-column>
      <el-table-column label="版式" width="170"><template #default="s">{{ piLayoutLabel(s.row.layoutCode) }}</template></el-table-column>
      <el-table-column prop="sellerName" label="卖方" /><el-table-column prop="bankName" label="银行" /><el-table-column prop="bankAccount" label="账号" />
      <el-table-column label="创建时间" min-width="175"><template #default="s">{{ formatDateTime(s.row.publishedAt) }}</template></el-table-column>
      <el-table-column label="修改时间" min-width="175"><template #default="s">{{ formatDateTime(s.row.publishedAt) }}</template></el-table-column>
      <el-table-column type="expand"><template #default="s"><el-descriptions :column="2" border><el-descriptions-item v-for="key in ['sellerAddress', 'sellerCountry', 'sellerContactName', 'sellerPhone', 'sellerEmail']" :key="key" :label="fields.find(f => f[0] === key)?.[1]">{{ s.row[key] || '历史未记录' }}</el-descriptions-item></el-descriptions></template></el-table-column>
      <el-table-column v-if="canManage || selectable" label="操作" :width="canManage && selectable ? 160 : 110" fixed="right"><template #default="s"><el-button v-if="canManage" link type="primary" @click="beginAdjustment(s.row)">调整模板</el-button><el-button v-if="selectable" link type="primary" :disabled="!isSelectablePiLayout(s.row.layoutCode)" @click="emit('select', s.row)">选用</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next, total" @current-change="load" />
    <el-dialog v-model="editing" :title="sourceTemplate ? '调整模板' : '新建模板'" width="min(780px, 94vw)" append-to-body :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
      <p v-if="sourceTemplate">正在调整 {{ sourceTemplate.templateCode }} · {{ sourceTemplate.templateVersion }}，已带入原资料，并生成可修改的新版本号。</p>
      <el-alert v-if="invalidFields.length" :title="`有 ${invalidFields.length} 处填写错误，请修改标红字段`" type="error" show-icon :closable="false">
        <div v-for="[key] in invalidFields" :key="key"><el-button link type="danger" class="template-error-link" @click="focusField(key)">{{ fieldErrors[key] }}</el-button></div>
      </el-alert>
      <el-alert v-else-if="formError" :title="formError" type="error" show-icon :closable="false" />
      <el-form v-if="canManage" ref="templateForm" label-position="top" class="template-form" :disabled="saving" @submit.prevent="publish">
        <el-form-item label="贸易类型"><el-select v-model="draft.tradeType" aria-label="贸易类型" :disabled="Boolean(sourceTemplate) || Boolean(tradeType)"><el-option value="DOMESTIC" label="内销" /><el-option value="EXPORT" label="外销" /></el-select></el-form-item>
        <el-form-item label="PI 版式" required><el-select v-model="draft.layoutCode" aria-label="PI 版式"><el-option v-for="layout in layoutOptions" :key="layout.value" :value="layout.value" :label="layout.label" /></el-select></el-form-item>
        <el-form-item v-for="[key, label] in fields" :key="key" :label="label" required :error="fieldErrors[key]" :data-template-field="key"><el-input v-model="draft[key]" :aria-label="label" :aria-invalid="Boolean(fieldErrors[key])" :disabled="key === 'templateCode' && Boolean(sourceTemplate)" :placeholder="key === 'templateCode' ? '例如 EXPORT_01（大写字母、数字、_、-）' : key === 'templateVersion' ? '例如 V1.0' : undefined" :type="['sellerAddress', 'reason'].includes(key) ? 'textarea' : key === 'sellerEmail' ? 'email' : 'text'" :maxlength="key === 'sellerAddress' ? 1000 : key === 'reason' ? 500 : key === 'sellerEmail' ? 254 : ['sellerCountry', 'sellerContactName'].includes(key) ? 100 : key === 'sellerPhone' || key === 'templateCode' ? 64 : key === 'templateVersion' ? 32 : 200" @blur="validateField(key)" @input="fieldErrors[key] && validateField(key)" /></el-form-item>
        <el-form-item label="补充联系方式（可选）"><el-input v-model="draft.sellerContact" aria-label="补充联系方式" maxlength="300" /></el-form-item>
      </el-form>
      <template #footer><div v-if="invalidFields.length" class="template-error-summary" role="alert">请修改：{{ invalidFields.map(([, label]) => label).join('、') }}。<el-button link type="danger" @click="focusField(invalidFields[0]![0])">定位第一处错误</el-button></div><div v-else-if="formError" class="template-error-summary" role="alert">{{ formError }}</div><el-button :disabled="saving" @click="editing = false">取消</el-button><el-button v-if="canManage" type="primary" :loading="saving" @click="publish">保存为新版本</el-button></template>
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
