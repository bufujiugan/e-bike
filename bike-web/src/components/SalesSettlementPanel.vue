<script setup lang="ts">
import { tr, trInline } from '../i18n/language'
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { hasPermission, type PermissionContext } from '../permissions'
import { createUuid } from '../utils/uuid'
import { europeSalesApi, type SettlementDraft, type SettlementEntry, type SettlementType } from '../europe-sales-api'
const props = defineProps<{ type: SettlementType; subjectId: number; currencyCode: string; context: PermissionContext | null }>()
const emit = defineEmits<{ changed: [] }>()
const supplementId = ref<number | null>(null)
const rows = ref<SettlementEntry[]>([]), busy = ref(false), saving = ref(false), error = ref(''), visible = ref(false)
const canWrite = computed(() => hasPermission(props.context, 'PI_PAYMENT_CONFIRM'))
const canReverse = computed(() => hasPermission(props.context, 'PI_PAYMENT_REVERSE'))
const title = computed(() => props.type === 'PI' ? '向 Totem 付款' : '工厂实际到账')
function blank(): SettlementDraft { return { requestId: createUuid(), amount: '', currencyCode: props.currencyCode, occurredAt: '', counterparty: '', referenceNo: '', remark: '' } }
const form = reactive(blank())
const total = computed(() => rows.value.filter(row => row.status === 'CONFIRMED').reduce((sum, row) => sum + cents(row.amount), 0n))
function cents(value: string | number) { const [whole, fraction = ''] = String(value).split('.'); return BigInt(whole || 0) * 100n + BigInt((fraction + '00').slice(0, 2)) }
function format(value: bigint) { return `${value / 100n}.${String(value % 100n).padStart(2, '0')}` }
function message(e: unknown) { return api.isAxiosError(e) ? e.response?.data?.message || '操作失败，请重试' : '操作失败，请重试' }
let generation = 0
async function load() {
  const token = ++generation; busy.value = true; error.value = ''; rows.value = []
  try { const result = await europeSalesApi.settlements(props.type, props.subjectId); if (token === generation) rows.value = result }
  catch (e) { if (token === generation) error.value = message(e) }
  finally { if (token === generation) busy.value = false }
}
function open() { supplementId.value=null; Object.assign(form, blank()); visible.value = true; error.value = '' }
function edit(row: SettlementEntry) { supplementId.value=row.id;Object.assign(form,row);visible.value=true;error.value='' }
async function save() {
  if (saving.value) return
  if (!/^(0|[1-9]\d{0,15})(\.\d{1,2})?$/.test(String(form.amount)) || cents(form.amount) <= 0n || !form.occurredAt) { error.value = '请填写真实金额和实际发生时间'; return }
  saving.value = true; error.value = ''
  try {
    rows.value = supplementId.value === null ? await europeSalesApi.record(props.type, props.subjectId, { ...form })
      : await europeSalesApi.supplement(props.type, props.subjectId, supplementId.value, { counterparty:form.counterparty,referenceNo:form.referenceNo,remark:form.remark })
    visible.value = false; emit('changed'); ElMessage.success(tr(supplementId.value === null ? '实际款项已登记' : '参考信息已补录'))
  }
  catch (e) { error.value = message(e) } finally { saving.value = false }
}
async function reverse(row: SettlementEntry) {
  try {
    const { value } = await ElMessageBox.prompt(tr('请填写更正原因。原记录保留，撤回后可重新登记正确金额。'), tr('撤回错误登记'), { inputValidator: value => Boolean(value?.trim()) || tr('请填写原因') })
    saving.value = true; rows.value = await europeSalesApi.reverse(props.type, props.subjectId, row.id, value.trim())
    emit('changed')
  } catch (e) { if (e !== 'cancel' && e !== 'close') error.value = message(e) } finally { saving.value = false }
}
watch(() => [props.type, props.subjectId], () => { visible.value = false; void load() }, { immediate: true })
</script>
<template>
  <section v-loading="busy" class="settlement-panel">
    <div class="settlement-toolbar"><strong>{{ tr(title) }}：{{ currencyCode }} {{ format(total) }}</strong><el-button v-if="canWrite" type="primary" :disabled="saving" @click="open">{{ trInline("登记") }}{{ tr(title) }}</el-button><el-button @click="load">{{ tr("刷新") }}</el-button></div>
    <p>{{ type === 'PI' ? tr('记录王总实际付给 Totem 的款项，与客户向销售侧付款分别记账。PI 修订共用此台账。') : tr('仅登记客户直接付给工厂的实际款项，不计入我方 PI 收款。流程中的“确认工厂已收款”由经办人另行确认，凭证可后补。') }}</p>
    <el-alert v-if="error" :title="tr(error)" type="error" :closable="false" />
    <el-table :data="rows" :empty-text='tr("尚未登记实际款项")'><el-table-column prop="occurredAt" :label='tr("实际发生时间")' min-width="170" /><el-table-column prop="amount" :label='tr("金额")' min-width="110" /><el-table-column prop="counterparty" :label='tr("收付方")' min-width="140" /><el-table-column prop="referenceNo" :label='tr("凭证号／参考号")' min-width="150" /><el-table-column prop="remark" :label='tr("说明")' min-width="170" /><el-table-column :label='tr("状态")' width="100"><template #default="{row}">{{ row.status === 'CONFIRMED' ? tr('有效') : tr('已撤回') }}</template></el-table-column><el-table-column v-if="canReverse || canWrite" :label='tr("更正")' width="140"><template #default="{row}"><el-button v-if="canWrite && row.status === 'CONFIRMED'" link type="primary" :disabled="saving" @click="edit(row)">{{ tr("补录") }}</el-button><el-button v-if="canReverse && row.status === 'CONFIRMED'" link type="danger" :disabled="saving" @click="reverse(row)">{{ tr("撤回") }}</el-button><span v-else :title="row.reversalReason">{{ row.reversalReason }}</span></template></el-table-column></el-table>
    <el-dialog v-model="visible" :title="tr(supplementId === null ? tr('登记{value0}', { value0: title }) : tr('补录款项参考信息'))" width="min(580px,95vw)" append-to-body :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
      <el-form label-position="top" :disabled="saving"><el-form-item :label='tr("实际金额")' required><el-input v-model="form.amount" :disabled="supplementId !== null" inputmode="decimal"><template #append>{{ currencyCode }}</template></el-input></el-form-item><el-form-item :label='tr("实际发生时间")' required><el-date-picker v-model="form.occurredAt" :disabled="supplementId !== null" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item><el-form-item :label='tr("收付方")'><el-input v-model="form.counterparty" maxlength="200" /></el-form-item><el-form-item :label='tr("凭证号／参考号（可后补）")'><el-input v-model="form.referenceNo" maxlength="300" /></el-form-item><el-form-item :label='tr("说明")'><el-input v-model="form.remark" type="textarea" maxlength="2000" /></el-form-item></el-form>
      <el-alert v-if="error" :title="tr(error)" type="error" :closable="false" /><template #footer><el-button :disabled="saving" @click="visible=false">{{ tr("取消") }}</el-button><el-button type="primary" :loading="saving" @click="save">{{ supplementId === null ? tr('保存实际款项') : tr('保存参考信息') }}</el-button></template>
    </el-dialog>
  </section>
</template>
<style scoped>.settlement-toolbar{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.settlement-toolbar .el-button{margin:0}.settlement-panel p{color:var(--el-text-color-secondary);line-height:1.8}.settlement-panel :deep(.el-date-editor){max-width:100%}</style>
