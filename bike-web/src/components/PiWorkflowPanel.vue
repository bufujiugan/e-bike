<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import type { PiWorkflow, PiWorkflowComplete, PiWorkflowStep } from '../pi-api'

const props = defineProps<{
  workflow: PiWorkflow | null
  busy: boolean
  error: string
  actionBusy: boolean
  canWrite: boolean
  canPay: boolean
  canRelease: boolean
  canLogistics: boolean
  canAfterSales: boolean
  piStatus: string
  currencyCode: string
  gateMet: boolean
  reservationBlocked: boolean
}>()
const emit = defineEmits<{
  action: [action: 'CONFIRM_PI' | 'REGISTER_PAYMENT' | 'RELEASE_PI' | 'REGISTER_LOGISTICS']
  complete: [stepCode: string, body: PiWorkflowComplete]
  productionUpdate: [body: PiWorkflowComplete]
  afterSales: []
}>()

const current = computed(() => props.workflow?.steps.find(step => step.code === props.workflow?.currentStep && step.status !== 'COMPLETED') || null)
const completed = computed(() => props.workflow?.steps.filter(step => step.status === 'COMPLETED').slice().reverse() || [])
const progress = computed(() => props.workflow?.totalCount ? Math.round(props.workflow.completedCount * 100 / props.workflow.totalCount) : 0)
const dialogVisible = ref(false)
const formError = ref('')
const form = reactive({
  note: '', contractNo: '', contractReady: false, paymentTableReady: false, productionTableReady: false,
  productionStatus: '', expectedCompletionDate: '', materialStatus: '', forwarderName: '', quotedAmount: '',
  quoteCurrency: '', domesticCarrier: '', domesticPrice: '', domesticTrackingNo: '', receivedDate: '',
  productionCompleted: false,
})

const actionLabel = computed(() => {
  if (!current.value) return ''
  if (current.value.code === 'PAYMENT_CONFIRMED') return props.piStatus === 'DRAFT' ? '确认并发布 PI' : '登记实际到账'
  if (current.value.code === 'TRACKING_RECORDED') return props.piStatus === 'RELEASED' ? '登记国际运输' : '放行发货'
  return current.value.code === 'PRODUCTION_FOLLOW_UP' ? '记录生产进度' : '填写并完成'
})
const actionDisabled = computed(() => {
  if (!props.canWrite || props.actionBusy || !current.value) return true
  if (current.value.code === 'PAYMENT_CONFIRMED') return props.piStatus === 'DRAFT' ? false : !props.canPay
  if (current.value.code === 'TRACKING_RECORDED') {
    if (props.piStatus === 'RELEASED') return !props.canLogistics
    return !props.canRelease || !props.gateMet || props.reservationBlocked
  }
  return false
})
const actionHint = computed(() => {
  if (!current.value) return '八个业务步骤已全部完成。'
  if (current.value.code === 'PAYMENT_CONFIRMED' && props.piStatus !== 'DRAFT' && !props.canPay) return '请交由有收款确认权限的业务人员处理。'
  if (current.value.code === 'TRACKING_RECORDED' && props.piStatus !== 'RELEASED') {
    if (props.reservationBlocked) return '来源库存占用已失效，请先重新核对库存。'
    if (!props.gateMet) return '累计到账尚未达到发货门槛。'
    if (!props.canRelease) return '请交由有发货放行权限的业务人员处理。'
  }
  if (current.value.code === 'TRACKING_RECORDED' && !props.canLogistics) return '请交由有物流维护权限的业务人员处理。'
  return ''
})

function startAction() {
  const step = current.value
  if (!step || actionDisabled.value) return
  if (step.code === 'PAYMENT_CONFIRMED') {
    emit('action', props.piStatus === 'DRAFT' ? 'CONFIRM_PI' : 'REGISTER_PAYMENT')
    return
  }
  if (step.code === 'TRACKING_RECORDED') {
    emit('action', props.piStatus === 'RELEASED' ? 'REGISTER_LOGISTICS' : 'RELEASE_PI')
    return
  }
  Object.assign(form, {
    note: '', contractNo: '', contractReady: false, paymentTableReady: false, productionTableReady: false,
    productionStatus: '', expectedCompletionDate: '', materialStatus: '', forwarderName: '', quotedAmount: '',
    quoteCurrency: props.currencyCode, domesticCarrier: '', domesticPrice: '', domesticTrackingNo: '', receivedDate: '',
    productionCompleted: false,
  })
  formError.value = ''
  dialogVisible.value = true
}

function validMoney(value: string) { return /^(0|[1-9]\d{0,15})(\.\d{1,2})?$/.test(value.trim()) }
function submit() {
  const code = current.value?.code
  if (!code || props.actionBusy) return
  const body: PiWorkflowComplete = { note: form.note.trim() || null }
  if (code === 'DOCUMENTS_READY') {
    if (!form.contractNo.trim() || !form.contractReady || !form.paymentTableReady || !form.productionTableReady) {
      formError.value = '请填写合同号，并确认合同、收付款表和生产表均已建立'; return
    }
    Object.assign(body, { contractNo: form.contractNo.trim(), contractReady: true, paymentTableReady: true, productionTableReady: true })
  } else if (code === 'PRODUCTION_FOLLOW_UP') {
    if (!form.productionStatus.trim()) { formError.value = '请填写工厂反馈的生产进度'; return }
    Object.assign(body, { productionStatus: form.productionStatus.trim(), expectedCompletionDate: form.expectedCompletionDate || null, materialStatus: form.materialStatus.trim() || null, productionCompleted: form.productionCompleted })
    formError.value = ''
    emit('productionUpdate', body)
    return
  } else if (code === 'FORWARDER_SELECTED') {
    if (!form.forwarderName.trim() || !validMoney(form.quotedAmount) || !/^[A-Za-z]{3}$/.test(form.quoteCurrency.trim())) {
      formError.value = '请填写货代名称、非负报价和 3 位币种代码'; return
    }
    Object.assign(body, { forwarderName: form.forwarderName.trim(), quotedAmount: form.quotedAmount.trim(), quoteCurrency: form.quoteCurrency.trim().toUpperCase() })
  } else if (code === 'DOMESTIC_EXPRESS_BOOKED') {
    if (!form.domesticCarrier.trim() || !validMoney(form.domesticPrice) || !form.domesticTrackingNo.trim()) {
      formError.value = '请填写国内快递公司、非负价格和快递单号'; return
    }
    Object.assign(body, { domesticCarrier: form.domesticCarrier.trim(), domesticPrice: form.domesticPrice.trim(), domesticTrackingNo: form.domesticTrackingNo.trim() })
  } else if (code === 'CUSTOMER_RECEIVED') {
    if (!form.receivedDate) { formError.value = '请填写客户确认收货日期'; return }
    Object.assign(body, { receivedDate: form.receivedDate })
  }
  formError.value = ''
  emit('complete', code, body)
}

function closeAfterSuccess() { dialogVisible.value = false }
defineExpose({ closeAfterSuccess })
watch(() => props.workflow?.currentStep, () => { if (dialogVisible.value) dialogVisible.value = false })

function summary(step: PiWorkflowStep) {
  const detail = step.details
  if (!detail) return step.note || ''
  if (step.code === 'DOCUMENTS_READY') return `合同号：${detail.contractNo || '—'} · 三张表已确认`
  if (step.code === 'PRODUCTION_FOLLOW_UP') return [detail.productionStatus, detail.expectedCompletionDate && `预计 ${detail.expectedCompletionDate}`, detail.materialStatus].filter(Boolean).join(' · ')
  if (step.code === 'FORWARDER_SELECTED') return `${detail.forwarderName || '—'} · ${detail.quoteCurrency || ''} ${detail.quotedAmount ?? ''}`
  if (step.code === 'DOMESTIC_EXPRESS_BOOKED') return `${detail.domesticCarrier || '—'} · ${detail.domesticTrackingNo || '—'}`
  if (step.code === 'CUSTOMER_RECEIVED') return `收货日期：${detail.receivedDate || '—'}`
  return step.note || ''
}
</script>

<template>
  <section class="workflow-band" aria-label="PI流程">
    <div class="workflow-head">
      <div><span class="workflow-kicker">履约进度</span><strong>{{ workflow?.completedCount || 0 }}/{{ workflow?.totalCount || 8 }}</strong></div>
      <el-progress :percentage="progress" :stroke-width="8" :show-text="false" />
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div v-if="busy" class="workflow-loading">正在加载流程...</div>
    <div v-else-if="current" class="workflow-current">
      <div class="workflow-step-number">{{ current.order }}</div>
      <div class="workflow-current-copy">
        <span>当前任务</span>
        <h3>{{ current.name }}</h3>
        <p v-if="actionHint">{{ actionHint }}</p>
      </div>
      <el-button type="primary" :loading="actionBusy" :disabled="actionDisabled" @click="startAction">{{ actionLabel }}</el-button>
    </div>
    <div v-if="current?.code === 'PRODUCTION_FOLLOW_UP' && workflow?.updates?.length" class="workflow-updates">
      <strong>最近生产跟进</strong>
      <ul><li v-for="item in workflow.updates.slice(0, 3)" :key="item.id"><span>{{ item.createdAt }}</span><p>{{ item.details.productionStatus }}<small v-if="item.details.expectedCompletionDate">预计 {{ item.details.expectedCompletionDate }}</small><small v-if="item.details.materialStatus">{{ item.details.materialStatus }}</small></p></li></ul>
    </div>
    <div v-else-if="workflow" class="workflow-complete"><div><strong>订单流程已完成</strong><span>客户收货已确认，后续问题请从售后模块登记。</span></div><el-button v-if="canAfterSales" plain @click="emit('afterSales')">进入售后案例</el-button></div>
    <details v-if="completed.length" class="workflow-history">
      <summary>查看已完成步骤（{{ completed.length }}）</summary>
      <ol><li v-for="step in completed" :key="step.code"><span>{{ step.order }}</span><div><strong>{{ step.name }}</strong><small>{{ summary(step) || step.completedAt || '已完成' }}</small></div></li></ol>
    </details>
  </section>

  <el-dialog v-model="dialogVisible" :title="current?.name || '完成流程步骤'" width="min(560px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!actionBusy">
    <el-form v-if="current" label-position="top">
      <template v-if="current.code === 'DOCUMENTS_READY'">
        <el-form-item label="合同号" required><el-input v-model="form.contractNo" maxlength="128" /></el-form-item>
        <div class="workflow-checks"><el-checkbox v-model="form.contractReady">合同已建立</el-checkbox><el-checkbox v-model="form.paymentTableReady">收付款表已建立</el-checkbox><el-checkbox v-model="form.productionTableReady">生产表已建立</el-checkbox></div>
      </template>
      <template v-else-if="current.code === 'PRODUCTION_FOLLOW_UP'">
        <el-form-item label="工厂反馈 / 当前进度" required><el-input v-model="form.productionStatus" type="textarea" maxlength="500" show-word-limit /></el-form-item>
        <el-form-item label="预计完成日期"><el-date-picker v-model="form.expectedCompletionDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="材料情况"><el-input v-model="form.materialStatus" type="textarea" maxlength="500" /></el-form-item>
        <el-checkbox v-model="form.productionCompleted" class="workflow-complete-check">生产已经完成，保存后进入货代询价</el-checkbox>
      </template>
      <template v-else-if="current.code === 'FORWARDER_SELECTED'">
        <el-form-item label="已选货代" required><el-input v-model="form.forwarderName" maxlength="128" /></el-form-item>
        <div class="workflow-money"><el-form-item label="确认报价" required><el-input v-model="form.quotedAmount" inputmode="decimal" /></el-form-item><el-form-item label="币种" required><el-input v-model="form.quoteCurrency" maxlength="3" /></el-form-item></div>
      </template>
      <template v-else-if="current.code === 'DOMESTIC_EXPRESS_BOOKED'">
        <el-form-item label="国内快递公司" required><el-input v-model="form.domesticCarrier" maxlength="128" /></el-form-item>
        <el-form-item label="国内快递单号" required><el-input v-model="form.domesticTrackingNo" maxlength="128" /></el-form-item>
        <el-form-item label="快递价格" required><el-input v-model="form.domesticPrice" inputmode="decimal"><template #append>{{ currencyCode }}</template></el-input></el-form-item>
      </template>
      <template v-else-if="current.code === 'CUSTOMER_RECEIVED'">
        <el-form-item label="客户确认收货日期" required><el-date-picker v-model="form.receivedDate" type="date" value-format="YYYY-MM-DD" :disabled-date="(date: Date) => date.getTime() > Date.now()" /></el-form-item>
      </template>
      <el-form-item label="补充说明"><el-input v-model="form.note" type="textarea" maxlength="500" show-word-limit /></el-form-item>
    </el-form>
    <el-alert v-if="formError" :title="formError" type="error" :closable="false" show-icon />
    <template #footer><el-button :disabled="actionBusy" @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="actionBusy" @click="submit">{{ current?.code === 'PRODUCTION_FOLLOW_UP' && !form.productionCompleted ? '保存本次跟进' : '保存并进入下一步' }}</el-button></template>
  </el-dialog>
</template>

<style scoped>
.workflow-band{margin:18px 0;padding:18px 0;border-top:1px solid #dce6f0;border-bottom:1px solid #dce6f0}.workflow-head{display:grid;grid-template-columns:auto minmax(180px,320px);align-items:center;justify-content:space-between;gap:20px}.workflow-head>div{display:flex;align-items:baseline;gap:10px}.workflow-kicker{font-size:13px;color:#65758a}.workflow-head strong{font-size:18px}.workflow-current{display:grid;grid-template-columns:36px minmax(0,1fr) auto;align-items:center;gap:14px;margin-top:16px;padding:16px;border-left:3px solid #1677c8;background:#f4f8fc}.workflow-step-number{width:32px;height:32px;display:grid;place-items:center;border-radius:50%;background:#1677c8;color:#fff;font-weight:700}.workflow-current-copy span{font-size:12px;color:#65758a}.workflow-current-copy h3{margin:3px 0 0;font-size:17px}.workflow-current-copy p{margin:5px 0 0;color:#a15c00;font-size:12px}.workflow-loading,.workflow-complete{margin-top:14px;color:#65758a}.workflow-complete{display:flex;align-items:center;justify-content:space-between;gap:12px}.workflow-complete>div{display:flex;flex-direction:column;gap:4px}.workflow-complete strong{color:#247447}.workflow-updates{margin:12px 0 0 52px}.workflow-updates>strong{font-size:12px;color:#65758a}.workflow-updates ul{margin:7px 0 0;padding:0;list-style:none}.workflow-updates li{display:grid;grid-template-columns:150px minmax(0,1fr);gap:10px;padding:6px 0;border-top:1px solid #edf1f5;font-size:12px}.workflow-updates li>span{color:#7b8798}.workflow-updates p{display:flex;gap:8px;flex-wrap:wrap;margin:0}.workflow-updates small{color:#65758a}.workflow-history{margin-top:12px}.workflow-history summary{cursor:pointer;color:#53657b;font-size:13px}.workflow-history ol{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px 20px;margin:12px 0 0;padding:0;list-style:none}.workflow-history li{display:flex;gap:9px;min-width:0}.workflow-history li>span{width:22px;height:22px;display:grid;place-items:center;border-radius:50%;background:#dff3e7;color:#247447;font-size:11px;flex:0 0 auto}.workflow-history li div{display:flex;flex-direction:column;min-width:0}.workflow-history li strong{font-size:13px}.workflow-history li small{color:#7b8798;overflow-wrap:anywhere}.workflow-checks{display:flex;flex-direction:column;margin-bottom:16px}.workflow-money{display:grid;grid-template-columns:minmax(0,1fr) 120px;gap:12px}.workflow-complete-check{margin-bottom:16px}.el-date-editor{width:100%}@media(max-width:620px){.workflow-head{grid-template-columns:1fr}.workflow-current{grid-template-columns:32px minmax(0,1fr)}.workflow-current .el-button{grid-column:1/-1;width:100%}.workflow-complete{align-items:flex-start;flex-direction:column}.workflow-updates{margin-left:0}.workflow-updates li{grid-template-columns:1fr;gap:2px}.workflow-history ol{grid-template-columns:1fr}.workflow-money{grid-template-columns:1fr}}
</style>
