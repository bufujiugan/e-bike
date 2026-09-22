<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { afterSales, afterSalesError, warrantyNames, type AfterSalesCase, type SolutionDraft, type SolutionVersion } from '../after-sales-api'
const props = defineProps<{ record: AfterSalesCase; canTech: boolean; canInternal: boolean }>()
const emit = defineEmits<{ changed: [record: AfterSalesCase] }>()
const saving = ref(false), history = ref<SolutionVersion[]>([]), selected = ref<number | null>(null), historyLoading = ref(false)
const names: Record<SolutionVersion['status'], string> = { DRAFT: '待技术确认', CONFIRMED: '技术已确认，待负责人审批', APPROVED: '已审批', SUPERSEDED: '已被新稿替代 / 已退回', LEGACY: '旧版文本资料，未推测配件审批' }
const displayed = computed(() => history.value.find(v => v.id === selected.value) || props.record.solutionVersion)
const editing = computed(() => props.canTech && props.record.status === 'UNDER_REVIEW' && (!selected.value || selected.value === props.record.solutionVersion?.id))
function blank(): SolutionDraft { return { technicalJudgment: '', solution: '', internalNote: '', previousVersionId: null, faultCause: '', customerActions: '', repairNotes: '', warrantyStatus: 'PENDING', currency: 'CNY', chargeReason: '', parts: [] } }
const draft = reactive<SolutionDraft>(blank())
function resetDraft() {
  const latest = props.record.solutionVersion
  Object.assign(draft, blank(), latest?.document || {}, { previousVersionId: latest?.id || null })
  draft.currency ||= 'CNY'; draft.warrantyStatus ||= 'PENDING'; draft.internalNote ||= ''
  draft.technicalJudgment ||= ''; draft.faultCause ||= ''; draft.customerActions ||= ''; draft.repairNotes ||= ''; draft.chargeReason ||= ''
  draft.parts = (latest?.document.parts || []).map(p => ({ ...p, unitCharge: String(p.unitCharge) }))
}
watch(() => [props.record.id, props.record.solutionVersion?.id, props.record.solutionVersion?.status], () => { selected.value = null; history.value = []; resetDraft() }, { immediate: true })
async function versions() { historyLoading.value = true; try { history.value = await afterSales.solutionVersions(props.record.id) } catch (e) { ElMessage.error(afterSalesError(e)) } finally { historyLoading.value = false } }
async function save() {
  if (saving.value) return
  if (!draft.solution.trim()) { ElMessage.warning('请填写维修方案'); return }
  if (draft.parts.some(p => !p.partCode.trim() || !p.partName.trim() || !/^\d+(\.\d{1,2})?$/.test(String(p.unitCharge)))) { ElMessage.warning('请填写配件编号、名称和最多两位小数的非负费用'); return }
  saving.value = true
  try { const current = await afterSales.solution(props.record.id, { ...draft, parts: draft.parts.map(p => ({ ...p })) }); emit('changed', current); ElMessage.success('新方案版本已保存，请核对后进行技术确认') }
  catch (e) { ElMessage.error(afterSalesError(e)) } finally { saving.value = false }
}
async function confirm() {
  const version = props.record.solutionVersion
  if (!version || saving.value) return
  try {
    await ElMessageBox.confirm(`确认已保存的第 ${version.revision} 版技术判断、保修责任、配件数量和费用。尚未保存的表单修改不会包含在此次确认中。`, '技术确认', { type: 'warning' })
    saving.value = true; emit('changed', await afterSales.confirmSolution(props.record.id, version.id)); ElMessage.success('技术确认完成，等待负责人审批')
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(afterSalesError(e)) } finally { saving.value = false }
}
const time = (value: string | null) => value ? value.replace('T', ' ').slice(0, 19) : '—'
</script>

<template>
  <section class="solution-workspace">
    <div class="version-bar"><strong>{{ displayed ? `方案第 ${displayed.revision} 版` : '尚无维修方案' }}</strong><el-tag v-if="displayed">{{ names[displayed.status] }}</el-tag><el-button :loading="historyLoading" @click="versions">查看版本历史</el-button><el-select v-if="history.length" v-model="selected" clearable placeholder="当前版本" aria-label="维修方案版本"><el-option v-for="v in history" :key="v.id" :value="v.id" :label="`第 ${v.revision} 版 · ${names[v.status]}`" /></el-select></div>
    <p class="help">配件数量是本案例按配件编号累计批准的总量，包含以前版本已计划和已发的数量。保存修改会产生新版本，并重新进行技术确认与审批。</p>
    <el-alert v-if="displayed?.status === 'LEGACY'" title="旧资料只保留原始文本。需要继续补发时，请重新审核并逐项核对历史补发与新配件清单。" type="warning" :closable="false" />
    <el-form v-if="editing" label-position="top" @submit.prevent="save">
      <div class="grid"><el-form-item label="技术判断（确认时必填）"><el-input v-model="draft.technicalJudgment" type="textarea" maxlength="4000" :rows="3" /></el-form-item><el-form-item label="故障原因"><el-input v-model="draft.faultCause" type="textarea" maxlength="2000" :rows="3" /></el-form-item></div>
      <el-form-item label="维修方案" required><el-input v-model="draft.solution" type="textarea" maxlength="4000" :rows="4" /></el-form-item>
      <div class="grid"><el-form-item label="客户需要执行的操作"><el-input v-model="draft.customerActions" type="textarea" maxlength="4000" :rows="3" /></el-form-item><el-form-item label="维修说明"><el-input v-model="draft.repairNotes" type="textarea" maxlength="2000" :rows="3" /></el-form-item><el-form-item label="保修责任"><el-select v-model="draft.warrantyStatus"><el-option v-for="(name, key) in warrantyNames" :key="key" :value="key" :label="name" /></el-select></el-form-item><el-form-item label="收费币种"><el-input v-model="draft.currency" maxlength="3" placeholder="CNY / USD / EUR" /></el-form-item></div>
      <el-form-item label="客户承担费用的原因（收费时必填）"><el-input v-model="draft.chargeReason" type="textarea" maxlength="1000" /></el-form-item>
      <h3>批准补发配件</h3><p class="help">无需补发时保持空清单。每件客户费用填写 0 表示免费。</p>
      <div v-for="(part, index) in draft.parts" :key="index" class="part grid">
        <el-form-item :label="`配件 ${index + 1} 编号`" required><el-input v-model="part.partCode" maxlength="128" /></el-form-item>
        <el-form-item label="配件名称" required><el-input v-model="part.partName" maxlength="255" /></el-form-item>
        <el-form-item label="累计批准数量" required><el-input-number v-model="part.quantity" :min="1" :max="1000000" :precision="0" /></el-form-item>
        <el-form-item label="每件客户费用" required><el-input v-model="part.unitCharge" inputmode="decimal" maxlength="19" /></el-form-item>
        <el-checkbox v-model="part.returnRequired">新补发要求退回旧件</el-checkbox><el-button type="danger" plain @click="draft.parts.splice(index, 1)">移除此条目</el-button>
      </div>
      <el-button :disabled="draft.parts.length >= 100" @click="draft.parts.push({ partCode: '', partName: '', quantity: 1, unitCharge: '0.00', returnRequired: false })">增加配件</el-button>
      <el-form-item label="内部备注（客户不可见）" class="internal"><el-input v-model="draft.internalNote" type="textarea" maxlength="4000" :rows="3" /></el-form-item>
      <div class="actions"><el-button type="primary" native-type="submit" :loading="saving">保存为新版本</el-button><el-button v-if="record.solutionVersion?.status === 'DRAFT'" type="success" :disabled="saving" @click="confirm">确认已保存版本</el-button></div>
    </el-form>
    <template v-else-if="displayed">
      <dl class="grid"><div><dt>技术判断</dt><dd>{{ displayed.document.technicalJudgment || '—' }}</dd></div><div><dt>故障原因</dt><dd>{{ displayed.document.faultCause || '—' }}</dd></div><div class="wide"><dt>维修方案</dt><dd>{{ displayed.document.solution }}</dd></div><div><dt>客户操作</dt><dd>{{ displayed.document.customerActions || '—' }}</dd></div><div><dt>维修说明</dt><dd>{{ displayed.document.repairNotes || '—' }}</dd></div><div><dt>保修责任</dt><dd>{{ warrantyNames[displayed.document.warrantyStatus] || '历史未确认' }}</dd></div><div><dt>费用责任</dt><dd>{{ displayed.document.chargeReason || '未登记客户承担费用' }}（{{ displayed.document.currency || '旧资料未明确币种' }}）</dd></div><div v-if="canInternal" class="wide"><dt>内部备注</dt><dd>{{ displayed.document.internalNote || '—' }}</dd></div><div><dt>技术确认</dt><dd>{{ time(displayed.confirmedAt) }}</dd></div><div><dt>负责人审批</dt><dd>{{ time(displayed.approvedAt) }}</dd></div></dl>
      <el-table :data="displayed.parts" empty-text="本版没有结构化配件条目"><el-table-column prop="partCode" label="编号" min-width="130" /><el-table-column prop="partName" label="配件" min-width="150" /><el-table-column prop="quantity" label="累计批准" width="100" /><el-table-column prop="allocatedQty" label="已计划 / 已发" width="130" /><el-table-column prop="unitCharge" label="每件客户费用" min-width="130" /><el-table-column label="旧件退回" width="110"><template #default="scope">{{ scope.row.returnRequired ? '需退回' : '无需退回' }}</template></el-table-column></el-table>
    </template>
  </section>
</template>

<style scoped>
.solution-workspace { display: grid; gap: 16px; }.version-bar,.actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }.version-bar .el-select { width: 320px; }.grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 14px 20px; }.wide { grid-column: 1/-1; }.help { margin: 0; color: #617080; font-size: 13px; line-height: 1.7; }.part { border: 1px solid #e0e7ee; border-radius: 8px; padding: 16px; margin: 12px 0; }.internal { margin-top: 20px; }dt { color: #718096; font-size: 12px; margin-bottom: 8px; }dd { margin: 0; white-space: pre-wrap; line-height: 1.7; overflow-wrap: anywhere; }.grid .el-select,.grid .el-input-number { width: 100%; }@media(max-width:680px){.grid{grid-template-columns:1fr;}.version-bar .el-select { width: 100%; }}
</style>
