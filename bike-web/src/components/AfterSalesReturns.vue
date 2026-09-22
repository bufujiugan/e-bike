<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { afterSales, afterSalesError, type CaseReturn, type CaseStatus, type ReturnAction, type ReturnRequest, type ReturnStatus } from '../after-sales-api'

const props = defineProps<{ caseId: number; status: CaseStatus; isAgent: boolean; canWrite: boolean; canShip: boolean; canTech: boolean; canReview: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const rows = ref<CaseReturn[]>([]), loading = ref(false), saving = ref(false), error = ref(''), visible = ref(false), selected = ref<CaseReturn | null>(null)
const form = reactive<ReturnRequest>({ revision: 1, action: 'REQUEST', warehouseName: '', recipient: '', phone: '', addressLine: '', postalCode: '', carrier: '', trackingNo: '', inspectionResult: '', reason: '' })
const names: Record<ReturnStatus, string> = { REQUIRED: '需要退回（待提供地址）', WAITING_CUSTOMER: '等待客户寄回', IN_TRANSIT: '客户已寄出', RECEIVED: '公司已收到', INSPECTED: '已检测', SCRAPPED: '已报废', COMPLETED: '已完成', WAIVED: '已豁免退回' }
const actions: Record<ReturnAction, string> = { REQUEST: '提供退回地址', SEND: '登记寄回物流', RECEIVE: '确认收到旧件', INSPECT: '登记检测结果', SCRAP: '确认报废', COMPLETE: '完成旧件处理', WAIVE: '豁免退回' }
const canAct = computed(() => ['APPROVED','IN_PROGRESS','WAITING_PARTS','SHIPPED','RESOLVED'].includes(props.status))
const needsReason = computed(() => ['SCRAP','COMPLETE','WAIVE'].includes(form.action))
const time = (value: string | null) => value ? value.replace('T',' ').slice(0,19) : '—'
let version = 0
async function load() {
  const current = ++version; loading.value = true; error.value = ''
  try { const result = await afterSales.returns(props.caseId); if (current === version) rows.value = result }
  catch (e) { if (current === version) { rows.value = []; error.value = afterSalesError(e) } }
  finally { if (current === version) loading.value = false }
}
function available(row: CaseReturn): ReturnAction[] {
  if (!canAct.value) return []
  const result: ReturnAction[] = []
  if (['REQUIRED','WAITING_CUSTOMER'].includes(row.status)) {
    if (props.canShip) result.push('REQUEST')
    if (props.canReview) result.push('WAIVE')
  }
  if (row.status === 'WAITING_CUSTOMER' && ((props.isAgent && props.canWrite) || props.canShip || props.canReview)) result.push('SEND')
  if (row.status === 'IN_TRANSIT' && props.canShip) result.push('RECEIVE')
  if (row.status === 'RECEIVED' && props.canTech) result.push('INSPECT')
  if (row.status === 'INSPECTED' && props.canReview) result.push('SCRAP','COMPLETE')
  if (row.status === 'SCRAPPED' && props.canReview) result.push('COMPLETE')
  return result
}
function open(row: CaseReturn, action: ReturnAction) {
  selected.value = row
  Object.assign(form, { revision: row.revision, action, warehouseName: row.warehouseName || '', recipient: row.recipient || '', phone: row.phone || '', addressLine: row.addressLine || '', postalCode: row.postalCode || '', carrier: '', trackingNo: '', inspectionResult: '', reason: '' })
  visible.value = true
}
async function save() {
  if (!selected.value || saving.value) return
  const required = form.action === 'REQUEST' ? [form.warehouseName,form.recipient,form.phone,form.addressLine,form.postalCode] : form.action === 'SEND' ? [form.carrier,form.trackingNo] : form.action === 'INSPECT' ? [form.inspectionResult] : needsReason.value ? [form.reason] : []
  if (required.some(v => !v.trim())) { ElMessage.warning('请填写完整的旧件处理资料'); return }
  saving.value = true
  try { await afterSales.returnAction(props.caseId,selected.value.shipmentId,{ ...form }); visible.value = false; await load(); emit('changed'); ElMessage.success('旧件处理记录已保存') }
  catch (e) { ElMessage.error(afterSalesError(e)); visible.value = false; await load() }
  finally { saving.value = false }
}
watch(() => props.caseId, () => { visible.value = false; selected.value = null; rows.value = []; void load() })
onMounted(load)
onUnmounted(() => { version++ })
</script>

<template>
  <section class="returns" aria-label="旧件处理">
    <div class="heading"><p>每项要求退回的补发配件单独记录物流、收件和检测。完成处理或由审核人员记录豁免后，案例才能归档。</p><el-button :loading="loading" @click="load">刷新旧件</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-empty v-if="!loading && !error && !rows.length" description="当前没有需要退回的旧件" />
    <div v-loading="loading" class="return-list">
      <article v-for="row in rows" :key="row.shipmentId" class="return-card">
        <header><h3>{{ row.partName }} × {{ row.quantity }}</h3><el-tag>{{ names[row.status] }}</el-tag></header>
        <dl>
          <div><dt>退回仓库 / 收件人</dt><dd>{{ row.warehouseName || '待提供' }} / {{ row.recipient || '—' }}</dd></div>
          <div><dt>联系电话 / 邮编</dt><dd>{{ row.phone || '—' }} / {{ row.postalCode || '—' }}</dd></div>
          <div class="wide"><dt>退回地址</dt><dd>{{ row.addressLine || '请等待售后人员提供退回地址后再寄出' }}</dd></div>
          <div><dt>寄回承运商 / 运单</dt><dd>{{ row.carrier || '—' }} / {{ row.trackingNo || '—' }}</dd></div>
          <div><dt>客户登记寄回时间</dt><dd>{{ time(row.sentAt) }}</dd></div>
          <div><dt>公司收件时间</dt><dd>{{ time(row.receivedAt) }}</dd></div>
          <div><dt>检测时间</dt><dd>{{ time(row.inspectedAt) }}</dd></div>
          <div class="wide"><dt>检测结果</dt><dd>{{ row.inspectionResult || '—' }}</dd></div>
          <div class="wide"><dt>处置 / 豁免说明</dt><dd>{{ row.dispositionNote || '—' }}</dd></div>
          <div><dt>完成时间</dt><dd>{{ time(row.completedAt) }}</dd></div>
        </dl>
        <div class="actions"><el-button v-for="action in available(row)" :key="action" :disabled="saving" @click="open(row,action)">{{ actions[action] }}</el-button></div>
      </article>
    </div>
    <el-dialog v-model="visible" :title="actions[form.action]" width="min(600px,95vw)" :close-on-click-modal="false" append-to-body>
      <el-form label-position="top" @submit.prevent="save">
        <template v-if="form.action === 'REQUEST'">
          <el-form-item label="退回仓库" required><el-input v-model="form.warehouseName" maxlength="128" /></el-form-item>
          <el-form-item label="收件人" required><el-input v-model="form.recipient" maxlength="100" /></el-form-item>
          <el-form-item label="联系电话" required><el-input v-model="form.phone" maxlength="64" /></el-form-item>
          <el-form-item label="完整退回地址" required><el-input v-model="form.addressLine" type="textarea" maxlength="500" /></el-form-item>
          <el-form-item label="邮政编码" required><el-input v-model="form.postalCode" maxlength="32" /></el-form-item>
        </template>
        <template v-else-if="form.action === 'SEND'">
          <p>{{ selected?.warehouseName }} · {{ selected?.recipient }} · {{ selected?.addressLine }}</p>
          <el-form-item label="寄回承运商" required><el-input v-model="form.carrier" maxlength="128" /></el-form-item>
          <el-form-item label="寄回运单号" required><el-input v-model="form.trackingNo" maxlength="128" /></el-form-item>
        </template>
        <el-form-item v-else-if="form.action === 'INSPECT'" label="检测结果（客户可见）" required><el-input v-model="form.inspectionResult" type="textarea" :rows="5" maxlength="2000" show-word-limit /></el-form-item>
        <el-form-item v-else-if="needsReason" label="处置原因 / 说明（客户可见）" required><el-input v-model="form.reason" type="textarea" :rows="4" maxlength="1000" show-word-limit /></el-form-item>
        <el-alert v-else title="请核实旧件实物与运单后确认收件。" type="info" :closable="false" />
      </el-form>
      <template #footer><el-button :disabled="saving" @click="visible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">确认保存</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.returns,.return-list { display: grid; gap: 16px; }.heading,header { display: flex; justify-content: space-between; gap: 12px; align-items: center; }.heading p { font-size: 13px; color: #617080; line-height: 1.7; }.return-card { padding: 20px; border: 1px solid #dfe7ee; border-radius: 8px; }h3 { margin: 0; font-size: 16px; }dl { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 16px; }dt { font-size: 12px; color: #617080; margin-bottom: 6px; }dd { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.6; }.wide { grid-column: 1 / -1; }.actions { display: flex; flex-wrap: wrap; gap: 8px; }.actions .el-button { margin-left: 0; }@media(max-width:680px) { dl { grid-template-columns: 1fr; }.heading,header { align-items: flex-start; flex-wrap: wrap; } }
</style>
