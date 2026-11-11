<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import PurchaseContractNumber from './PurchaseContractNumber.vue'
import type { FulfillmentRules } from '../fulfillment-api'
import { piApi, type PiWorkflow, type PiWorkflowComplete, type PiWorkflowStep, type PiLogistics } from '../pi-api'
const props = defineProps<{ workflow: PiWorkflow | null; rules?: FulfillmentRules; busy: boolean; error: string; actionBusy: boolean; canWrite: boolean; canPay: boolean; canRelease: boolean; canLogistics: boolean; canAfterSales: boolean; piStatus: string; currencyCode: string; gateMet: boolean; reservationBlocked: boolean; piId: number; logistics: PiLogistics[]; domesticShipment?: boolean | null }>()
const emit = defineEmits<{ action: [action: 'CONFIRM_PI' | 'REGISTER_PAYMENT' | 'RELEASE_PI' | 'REGISTER_LOGISTICS']; complete: [stepCode: string, body: PiWorkflowComplete]; productionUpdate: [body: PiWorkflowComplete]; afterSales: [] }>()
const current = computed(() => props.workflow?.steps.find(s => s.code === props.workflow?.currentStep && s.status !== 'COMPLETED') || null)
const completed = computed(() => props.workflow?.steps.filter(s => s.status === 'COMPLETED') || [])
const purchaseContract = computed(() => props.workflow?.steps.find(step => step.code === 'PURCHASE_CONTRACT_READY')?.details)
const purchaseContractParty = computed(() => purchaseContract.value?.purchaseContractParty === 'YINSANHUAN' ? '银三环' : purchaseContract.value?.purchaseContractParty)
const customerForwarder = computed(() => props.rules?.deliveryMode === 'CUSTOMER_PICKUP' || props.workflow?.steps.find(s => s.code === 'FORWARDER_SELECTED')?.details?.forwarderResponsibility === 'CUSTOMER')
const progress = computed(() => props.workflow?.totalCount ? Math.round(props.workflow.completedCount * 100 / props.workflow.totalCount) : 0)
const dialogVisible = ref(false), formError = ref(''), activeCode = ref(''), attachmentsBusy = ref(false)
const proofs = ref<{ id: string; fileName: string }[]>([])
const documentSteps = ['DISPATCH_SHEET_SENT','CI_SENT','RD_SENT','RD_SIGNED','CMR_COLLECTED']
const configurableSteps = [...documentSteps,'CREDIT_APPROVED','GOODS_PREPARED','CASE_CLOSED']
const form = reactive({ note: '', contractNo: '', contractReady: false, paymentTableReady: false, purchaseContractNo: '', purchaseContractParty: '', forwarderResponsibility: 'SELLER', forwarderName: '', forwarderAddress: '', forwarderContact: '', domesticCarrier: '', domesticPrice: '', domesticTrackingNo: '', warehouseArrivalDate: '', arrivalProof: '', internationalTrackingNo: '', internationalPrice: '', internationalCurrency: '', receivedDate: '' })
const disabled = computed(() => props.actionBusy || (current.value?.code === 'INVENTORY_RESERVED' ? !props.canPay && !props.canRelease : current.value?.code === 'CREDIT_APPROVED' ? !props.canRelease : ['PAYMENT_CONFIRMED','PAYMENT_SETTLED'].includes(current.value?.code || '')
  ? !props.canPay : current.value?.code === 'STOCK_DISPATCHED' ? !props.canRelease : !props.canWrite))
const production = reactive({ productionStatus: '', expectedCompletionDate: '', materialStatus: '' })
const actionLabel = computed(() => {
  switch (current.value?.code) {
    case 'DOCUMENTS_READY': return props.piStatus === 'DRAFT' ? '确认并发布 PI' : '确认销售合同与收付款表'
    case 'PAYMENT_CONFIRMED': case 'PAYMENT_SETTLED': return '登记实际到账'
    case 'CREDIT_APPROVED': return '确认后付款授权'
    case 'INVENTORY_RESERVED': return '重试按仓锁库'
    case 'STOCK_DISPATCHED': return props.piStatus === 'RELEASED' ? '等待仓库办理出库' : '放行出库'
    case 'TRACKING_RECORDED': return '登记发货号及实际费用'
    default: return '填写并完成'
  }
})
async function openForm(code: string) {
  activeCode.value = code; formError.value = ''; proofs.value = []
  Object.assign(form, { note: '', contractNo: '', contractReady: false, paymentTableReady: false, purchaseContractNo: '', purchaseContractParty: '', forwarderResponsibility: 'SELLER', forwarderName: '', forwarderAddress: '', forwarderContact: '', domesticCarrier: '', domesticPrice: '', domesticTrackingNo: '', warehouseArrivalDate: '', arrivalProof: '', internationalTrackingNo: '', internationalPrice: '', internationalCurrency: props.currencyCode, receivedDate: '' })
  Object.assign(production, { productionStatus: '', expectedCompletionDate: '', materialStatus: '' })
  if (code === 'PURCHASE_CONTRACT_READY') {
    const saved = props.workflow?.steps.find(step => step.code === code)
    Object.assign(form, { purchaseContractNo: saved?.details?.purchaseContractNo || '', purchaseContractParty: saved?.details?.purchaseContractParty || '', note: saved?.note || '' })
  }
  form.forwarderResponsibility = props.rules?.deliveryMode === 'CUSTOMER_PICKUP' ? 'CUSTOMER' : 'SELLER'
  dialogVisible.value = true
  if (documentSteps.includes(code)) {
    attachmentsBusy.value = true
    try { proofs.value = await piApi.confirmationAttachments(props.piId) } catch { formError.value = '业务单据读取失败，请重试' } finally { attachmentsBusy.value = false }
  }
  if (code === 'WAREHOUSE_ARRIVED') {
    attachmentsBusy.value = true
    try { const lists = await Promise.all(props.logistics.filter(l => l.status !== 'WITHDRAWN').map(l => piApi.logisticsAttachments(props.piId, l.id))); proofs.value = lists.flat() }
    catch { formError.value = '物流附件读取失败，请重试'; }
    finally { attachmentsBusy.value = false }
  }
}
function startAction() {
  const code = current.value?.code
  if (!code || disabled.value) return
  if (code === 'DOCUMENTS_READY' && props.piStatus === 'DRAFT') { emit('action', 'CONFIRM_PI'); return }
  if (code === 'PAYMENT_CONFIRMED' || code === 'PAYMENT_SETTLED') { emit('action', 'REGISTER_PAYMENT'); return }
  if (code === 'INVENTORY_RESERVED') { emit('complete', code, {}); return }
  if (code === 'STOCK_DISPATCHED') { if (props.piStatus !== 'RELEASED') emit('action', 'RELEASE_PI'); return }
  void openForm(code)
}
function money(value: string) { return /^(0|[1-9]\d{0,15})(\.\d{1,2})?$/.test(value.trim()) }
function submit() {
  const code = activeCode.value, body: PiWorkflowComplete = { note: form.note.trim() || null }
  if (code === 'PRODUCTION_FOLLOW_UP') {
    if (!production.productionStatus.trim()) { formError.value = '请填写生产进度'; return }
    emit('productionUpdate', { ...body, productionStatus: production.productionStatus.trim(), expectedCompletionDate: production.expectedCompletionDate || null, materialStatus: production.materialStatus.trim() || null })
    return
  }
  if (configurableSteps.includes(code)) {
    if (!body.note) { formError.value = '请填写实际办理情况或授权依据'; return }
    if (documentSteps.includes(code)) {
      if (!form.arrivalProof || !form.receivedDate) { formError.value = '请选择本PI的业务单据附件及实际日期'; return }
      Object.assign(body, { arrivalProof: form.arrivalProof, receivedDate: form.receivedDate })
    }
  }
  if (code === 'DOCUMENTS_READY') {
    if (((props.rules?.salesContractRequired ?? true) && (!form.contractNo.trim() || !form.contractReady)) || !form.paymentTableReady) { formError.value = '请填写销售合同号，并确认合同及收付款表'; return }
    Object.assign(body, { contractNo: form.contractNo.trim() || null, contractReady: form.contractReady, paymentTableReady: true })
  } else if (code === 'PURCHASE_CONTRACT_READY') {
    if (!form.purchaseContractNo.trim() || !form.purchaseContractParty.trim()) { formError.value = '请填写购销合同号和合同方'; return }
    Object.assign(body, { purchaseContractNo: form.purchaseContractNo.trim(), purchaseContractParty: form.purchaseContractParty.trim() })
  } else if (code === 'FORWARDER_SELECTED') {
    if (![form.forwarderName, form.forwarderAddress, form.forwarderContact].every(v => v.trim())) { formError.value = '请填写货代、仓库地址及收货信息'; return }
    Object.assign(body, { forwarderResponsibility: form.forwarderResponsibility, forwarderName: form.forwarderName.trim(), forwarderAddress: form.forwarderAddress.trim(), forwarderContact: form.forwarderContact.trim() })
  } else if (code === 'DOMESTIC_EXPRESS_BOOKED') {
    if (!customerForwarder.value && (!form.domesticCarrier.trim() || !form.domesticTrackingNo.trim() || !money(form.domesticPrice))) { formError.value = '我方送仓须填写承运商、单号及有效运费'; return }
    if (customerForwarder.value && !body.note) { formError.value = '请说明客户指定货代的送仓安排'; return }
    Object.assign(body, { domesticCarrier: form.domesticCarrier || null, domesticTrackingNo: form.domesticTrackingNo || null, domesticPrice: form.domesticPrice || null })
  } else if (code === 'WAREHOUSE_ARRIVED') {
    if (!form.warehouseArrivalDate || !form.arrivalProof) { formError.value = '请填写到仓日期并选择本PI物流附件作为凭证'; return }
    Object.assign(body, { warehouseArrivalDate: form.warehouseArrivalDate, arrivalProof: form.arrivalProof })
  } else if (code === 'TRACKING_RECORDED') {
    if (!customerForwarder.value && (!form.internationalTrackingNo.trim() || !money(form.internationalPrice) || !/^[A-Za-z]{3}$/.test(form.internationalCurrency))) { formError.value = '请填写货代发货号、实际费用及币种'; return }
    if (customerForwarder.value && !body.note) { formError.value = '请说明已获取的客户货代信息，未知部分如实说明'; return }
    Object.assign(body, { internationalTrackingNo: form.internationalTrackingNo || null, internationalPrice: form.internationalPrice || null, internationalCurrency: form.internationalCurrency || null })
  } else if (code === 'CUSTOMER_RECEIVED') {
    if (!form.receivedDate) { formError.value = '请填写全部货物签收日期'; return }
    body.receivedDate = form.receivedDate
  }
  formError.value = ''; emit('complete', code, body)
}
function summary(step: PiWorkflowStep) { const d = step.details; return [d?.contractNo, d?.purchaseContractNo, d?.forwarderName, d?.domesticTrackingNo, d?.warehouseArrivalDate, d?.internationalTrackingNo, d?.receivedDate, step.note].filter(Boolean).join(' · ') }
watch(() => props.workflow, () => { if (props.workflow?.steps.some(s => s.code === activeCode.value && s.status === 'COMPLETED')) dialogVisible.value = false })
watch(() => props.workflow?.updates.length, (count, before) => { if (activeCode.value === 'PRODUCTION_FOLLOW_UP' && count !== before) dialogVisible.value = false })
function closeAfterSuccess() { dialogVisible.value = false }
defineExpose({ closeAfterSuccess })
</script>
<template>
  <section class="workflow-band" aria-label="PI流程">
    <div class="workflow-head"><div><span class="workflow-kicker">履约进度</span><strong>{{ workflow?.completedCount || 0 }}/{{ workflow?.totalCount || 11 }}</strong></div><el-progress :percentage="progress" /></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="busy">正在读取流程…</p>
    <div v-else-if="current" class="workflow-current"><div class="workflow-step-number">{{ current.order }}</div><div class="workflow-current-copy"><span>当前任务</span><h3>{{ current.name }}</h3><p v-if="current.code === 'STOCK_DISPATCHED'">放行后由仓库扫码出库；全部货物出库后自动进入下一步。</p><p v-if="current.code === 'INVENTORY_RESERVED'">锁库不足时到账记录保留，补足指定仓库库存后重试。</p></div><el-button type="primary" :loading="actionBusy" :disabled="disabled || current.code === 'PAYMENT_CONFIRMED' && !canPay || current.code === 'INVENTORY_RESERVED' && !canPay && !canRelease || current.code === 'STOCK_DISPATCHED' && (piStatus === 'RELEASED' || !canRelease || !gateMet)" @click="startAction">{{ actionLabel }}</el-button></div>
    <div v-else-if="workflow" class="workflow-complete"><strong>{{ rules ? '交付、结算及所需单据已核对，业务已结案' : '全部货物签收，订单流程完成' }}</strong><el-button v-if="canAfterSales" @click="emit('afterSales')">进入售后</el-button></div>
    <div v-if="rules && canWrite && !['DRAFT','CANCELLED','SUPERSEDED'].includes(piStatus)" class="workflow-actions"><span>其他待办（按实际进度办理）：</span><el-button v-for="step in workflow?.steps.filter(s => s.status !== 'COMPLETED' && ['PURCHASE_CONTRACT_READY','FORWARDER_SELECTED','CI_SENT','RD_SENT','RD_SIGNED','CMR_COLLECTED'].includes(s.code))" :key="step.code" link :disabled="actionBusy" @click="openForm(step.code)">{{ step.name }}</el-button><el-button v-if="canPay" link @click="emit('action','REGISTER_PAYMENT')">登记到账</el-button></div>
    <div class="workflow-actions"><el-button v-if="canWrite && !['CANCELLED','SUPERSEDED'].includes(piStatus)" link @click="openForm('PRODUCTION_FOLLOW_UP')">记录生产跟进（可选）</el-button><el-button v-if="!rules && domesticShipment && canWrite && workflow?.steps.find(s => s.code === 'DOCUMENTS_READY')?.status === 'COMPLETED' && workflow?.steps.find(s => s.code === 'PURCHASE_CONTRACT_READY')?.status !== 'COMPLETED'" link @click="openForm('PURCHASE_CONTRACT_READY')">提前登记国内购销合同</el-button><el-button v-if="canLogistics && ['WAREHOUSE_ARRIVED','TRACKING_RECORDED'].includes(current?.code || '')" link @click="emit('action', 'REGISTER_LOGISTICS')">登记批次物流 / 上传到仓凭证请使用物流附件</el-button></div>
    <el-descriptions v-if="purchaseContract?.purchaseContractNo || purchaseContractParty" title="本单购销合同" :column="1" border class="workflow-contract"><el-descriptions-item label="购销合同方">{{ purchaseContractParty || '未登记' }}</el-descriptions-item><el-descriptions-item label="购销合同号"><PurchaseContractNumber :value="purchaseContract?.purchaseContractNo" /></el-descriptions-item></el-descriptions>
    <details v-if="workflow?.updates.length" class="workflow-history"><summary>生产跟进记录（可选）</summary><ol><li v-for="update in workflow.updates" :key="update.id"><div><strong>{{ update.details?.productionStatus }}</strong><small>{{ update.createdAt }} · {{ update.details?.materialStatus }} {{ update.note }}</small></div></li></ol></details>
    <details v-if="completed.length" class="workflow-history"><summary>查看已完成步骤（{{ completed.length }}）</summary><ol><li v-for="step in completed" :key="step.code"><span>{{ step.order }}</span><div><strong>{{ step.name }}</strong><small>{{ summary(step) || '已完成' }}</small></div></li></ol></details>
  </section>
  <el-dialog v-model="dialogVisible" :title="activeCode === 'PRODUCTION_FOLLOW_UP' ? '记录生产跟进' : workflow?.steps.find(s => s.code === activeCode)?.name || '业务确认'" width="min(600px,94vw)" :close-on-click-modal="false">
    <el-form label-position="top">
      <template v-if="configurableSteps.includes(activeCode)"><p v-if="activeCode === 'CREDIT_APPROVED'">批准后按本单约定保留库存，实际到账仍需单独登记。请核对客户、金额、付款期限并填写授权依据。</p><template v-if="documentSteps.includes(activeCode)"><p>先在 PI 详情“业务单据附件”上传对应文件。发送节点记录经办人实际发送情况，不会自动向客户或工厂发送。</p><el-form-item label="本PI业务单据附件" required><el-select v-model="form.arrivalProof" :loading="attachmentsBusy"><el-option v-for="proof in proofs" :key="proof.id" :value="proof.id" :label="proof.fileName" /></el-select></el-form-item><el-form-item label="实际发送 / 回收日期" required><el-date-picker v-model="form.receivedDate" value-format="YYYY-MM-DD" /></el-form-item></template></template>
      <template v-if="activeCode === 'PRODUCTION_FOLLOW_UP'"><el-form-item label="生产进度" required><el-input v-model="production.productionStatus" type="textarea" maxlength="500" /></el-form-item><el-form-item label="预计完成日期"><el-date-picker v-model="production.expectedCompletionDate" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="材料情况"><el-input v-model="production.materialStatus" maxlength="500" /></el-form-item></template>
      <template v-else-if="activeCode === 'DOCUMENTS_READY'"><el-form-item label="销售合同号" :required="rules?.salesContractRequired ?? true"><el-input v-model="form.contractNo" maxlength="128" /></el-form-item><el-checkbox v-model="form.contractReady">PI、销售合同及付款信息已确认</el-checkbox><el-checkbox v-model="form.paymentTableReady">收付款表已建立（应收金额、币种、收款主体已核对）</el-checkbox></template>
      <template v-else-if="activeCode === 'PURCHASE_CONTRACT_READY'"><el-form-item label="购销合同方" required><el-select v-if="!rules" v-model="form.purchaseContractParty"><el-option value="SAVA" label="SAVA" /><el-option value="YINSANHUAN" label="银三环" /></el-select><el-input v-else v-model="form.purchaseContractParty" maxlength="200" placeholder="实际合同方" /></el-form-item><el-form-item label="购销合同号" required><el-input v-model="form.purchaseContractNo" maxlength="128" /></el-form-item></template>
      <template v-else-if="activeCode === 'FORWARDER_SELECTED'"><el-form-item label="由谁联系货代" required><el-radio-group v-model="form.forwarderResponsibility" :disabled="Boolean(rules)"><el-radio value="CUSTOMER">客户联系</el-radio><el-radio value="SELLER">我方联系</el-radio></el-radio-group></el-form-item><el-form-item label="货代名称" required><el-input v-model="form.forwarderName" maxlength="128" /></el-form-item><el-form-item :label="customerForwarder && rules ? '实际提货仓库地址' : '货代仓库地址'" required><el-input v-model="form.forwarderAddress" type="textarea" maxlength="1000" /></el-form-item><el-form-item :label="customerForwarder && rules ? '提货联系人及联系方式' : '收货人及联系方式'" required><el-input v-model="form.forwarderContact" maxlength="300" /></el-form-item></template>
      <template v-else-if="activeCode === 'DOMESTIC_EXPRESS_BOOKED'"><p v-if="customerForwarder">客户安排的运输按可获取信息登记，在补充说明中确认送仓安排。</p><el-form-item label="送仓承运商" :required="!customerForwarder"><el-input v-model="form.domesticCarrier" placeholder="货拉拉 / 跨越 / 顺丰 / 其他" maxlength="128" /></el-form-item><el-form-item label="送仓运单号" :required="!customerForwarder"><el-input v-model="form.domesticTrackingNo" maxlength="128" /></el-form-item><el-form-item :label="'运费 / ' + currencyCode" :required="!customerForwarder"><el-input v-model="form.domesticPrice" inputmode="decimal" /></el-form-item></template>
      <template v-else-if="activeCode === 'WAREHOUSE_ARRIVED'"><el-form-item label="实际到仓日期" required><el-date-picker v-model="form.warehouseArrivalDate" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="到货凭证（本PI物流附件）" required><el-select v-model="form.arrivalProof" :loading="attachmentsBusy"><el-option v-for="proof in proofs" :key="proof.id" :value="proof.id" :label="proof.fileName" /></el-select></el-form-item><p>先在物流记录的附件入口上传签收单或到仓证明，再选择。</p></template>
      <template v-else-if="activeCode === 'TRACKING_RECORDED'"><p>请先为每个实际出库批次登记发运信息。客户安排的按可获取信息填写。</p><el-form-item label="货代发货号" :required="!customerForwarder"><el-input v-model="form.internationalTrackingNo" maxlength="128" /></el-form-item><el-form-item label="实际运输费用" :required="!customerForwarder"><el-input v-model="form.internationalPrice" inputmode="decimal" /></el-form-item><el-form-item label="费用币种" :required="!customerForwarder"><el-input v-model="form.internationalCurrency" maxlength="3" /></el-form-item></template>
      <template v-else-if="activeCode === 'CUSTOMER_RECEIVED'"><el-form-item label="全部货物签收日期" required><el-date-picker v-model="form.receivedDate" value-format="YYYY-MM-DD" /></el-form-item></template>
      <el-form-item :label="configurableSteps.includes(activeCode) ? '办理情况 / 接收人 / 授权依据' : '补充说明'" :required="configurableSteps.includes(activeCode)"><el-input v-model="form.note" type="textarea" maxlength="500" /></el-form-item>
    </el-form><el-alert v-if="formError" :title="formError" type="error" :closable="false" />
    <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="actionBusy" :disabled="attachmentsBusy" @click="submit">保存并确认</el-button></template>
  </el-dialog>
</template>
<style scoped>
:deep(.el-checkbox){height:auto;min-height:32px;align-items:flex-start;margin-right:0;max-width:100%}
:deep(.el-checkbox__input){margin-top:4px}
:deep(.el-checkbox__label){white-space:normal;line-height:1.5}
.workflow-actions{display:flex;flex-wrap:wrap;gap:8px}

.workflow-band{margin:18px 0;padding:18px 0;border-top:1px solid #dce6f0;border-bottom:1px solid #dce6f0}.workflow-head{display:grid;grid-template-columns:auto minmax(180px,320px);align-items:center;justify-content:space-between;gap:20px}.workflow-head>div{display:flex;align-items:baseline;gap:10px}.workflow-kicker{font-size:13px;color:#65758a}.workflow-head strong{font-size:18px}.workflow-current{display:grid;grid-template-columns:36px minmax(0,1fr) auto;align-items:center;gap:14px;margin-top:16px;padding:16px;border-left:3px solid #1677c8;background:#f4f8fc}.workflow-step-number{width:32px;height:32px;display:grid;place-items:center;border-radius:50%;background:#1677c8;color:#fff;font-weight:700}.workflow-current-copy span{font-size:12px;color:#65758a}.workflow-current-copy h3{margin:3px 0 0;font-size:17px}.workflow-current-copy p{margin:5px 0 0;color:#a15c00;font-size:12px}.workflow-loading,.workflow-complete{margin-top:14px;color:#65758a}.workflow-complete{display:flex;align-items:center;justify-content:space-between;gap:12px}.workflow-complete>div{display:flex;flex-direction:column;gap:4px}.workflow-complete strong{color:#247447}.workflow-updates{margin:12px 0 0 52px}.workflow-updates>strong{font-size:12px;color:#65758a}.workflow-updates ul{margin:7px 0 0;padding:0;list-style:none}.workflow-updates li{display:grid;grid-template-columns:150px minmax(0,1fr);gap:10px;padding:6px 0;border-top:1px solid #edf1f5;font-size:12px}.workflow-updates li>span{color:#7b8798}.workflow-updates p{display:flex;gap:8px;flex-wrap:wrap;margin:0}.workflow-updates small{color:#65758a}.workflow-history{margin-top:12px}.workflow-history summary{cursor:pointer;color:#53657b;font-size:13px}.workflow-history ol{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px 20px;margin:12px 0 0;padding:0;list-style:none}.workflow-history li{display:flex;gap:9px;min-width:0}.workflow-history li>span{width:22px;height:22px;display:grid;place-items:center;border-radius:50%;background:#dff3e7;color:#247447;font-size:11px;flex:0 0 auto}.workflow-history li div{display:flex;flex-direction:column;min-width:0}.workflow-history li strong{font-size:13px}.workflow-history li small{color:#7b8798;overflow-wrap:anywhere}.workflow-checks{display:flex;flex-direction:column;margin-bottom:16px}.workflow-money{display:grid;grid-template-columns:minmax(0,1fr) 120px;gap:12px}.workflow-complete-check{margin-bottom:16px}.el-date-editor{width:100%}@media(max-width:620px){.workflow-head{grid-template-columns:1fr}.workflow-current{grid-template-columns:32px minmax(0,1fr)}.workflow-current .el-button{grid-column:1/-1;width:100%}.workflow-complete{align-items:flex-start;flex-direction:column}.workflow-updates{margin-left:0}.workflow-updates li{grid-template-columns:1fr;gap:2px}.workflow-history ol{grid-template-columns:1fr}.workflow-money{grid-template-columns:1fr}}
</style>
