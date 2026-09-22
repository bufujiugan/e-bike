<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { afterSales, afterSalesError, caseStatuses, warrantyNames, type AfterSalesCase, type CaseDraft, type CaseStatus, type CaseShipment, type CaseOptions, type CaseAttachment, type CaseMessage, type CaseFilters, type CaseDashboard, type CaseQueue } from '../after-sales-api'
import { afterSalesCompanies, blankContact, type CompanyContact, type StaffOption } from '../after-sales-company-api'
import AfterSalesCompanyWorkspace from './AfterSalesCompanyWorkspace.vue'
import AfterSalesContactFields from './AfterSalesContactFields.vue'
import AfterSalesReturns from './AfterSalesReturns.vue'

type Context = { username: string; userType: string; agentId: number | null; roles: string[]; permissions: string[] }
const props = defineProps<{ authContext: Context | null }>()
const isAgent = computed(() => props.authContext?.userType === 'AGENT' || Boolean(props.authContext?.roles.includes('WEB_AGENT')))
const isPda = computed(() => props.authContext?.userType === 'PDA_OPERATOR' || Boolean(props.authContext?.roles.includes('PDA_OPERATOR')))
function permission(name: string) { return !isPda.value && Boolean(props.authContext?.roles.includes('SUPER_ADMIN') || props.authContext?.permissions.includes(name)) }
const canRead = computed(() => permission('AFTER_SALES_READ'))
const canWrite = computed(() => permission('AFTER_SALES_WRITE'))
const canReview = computed(() => !isAgent.value && permission('AFTER_SALES_REVIEW'))
const canTech = computed(() => !isAgent.value && permission('AFTER_SALES_TECH'))
const canShip = computed(() => !isAgent.value && permission('AFTER_SALES_LOGISTICS'))
const canInternal = computed(() => canReview.value || canTech.value)
const canMessage = computed(() => canWrite.value || canReview.value || canTech.value || canShip.value)
const canAssign = computed(() => !isAgent.value && permission('AFTER_SALES_ASSIGN'))
const companyVisible = ref(false), assignmentVisible = ref(false), assignmentOptions = ref<StaffOption[]>([]), assignee = ref<number | null>(null)
const keyword = ref(''), status = ref(''), page = ref(1), total = ref(0)
function blankFilters(): CaseFilters { return { companyName: '', contactName: '', frameSn: '', modelName: '', faultType: '', countryCode: '', assigneeName: '', createdFrom: null, createdTo: null, queue: '' } }
const filters = reactive<CaseFilters>(blankFilters()), advanced = ref(false), dashboard = ref<CaseDashboard | null>(null)
const dashboardCards: { key: keyof CaseDashboard; queue: CaseQueue; label: string }[] = [
  { key: 'total', queue: '', label: '全部案例' }, { key: 'pending', queue: 'PENDING', label: '待处理' },
  { key: 'review', queue: 'REVIEW', label: '审核中' }, { key: 'waitingCustomer', queue: 'WAITING_CUSTOMER', label: '等待客户回复' },
  { key: 'processing', queue: 'PROCESSING', label: '处理中' }, { key: 'waitingShipment', queue: 'WAITING_SHIPMENT', label: '待发货' },
  { key: 'shipped', queue: 'SHIPPED', label: '已发货' }, { key: 'completed', queue: 'COMPLETED', label: '已归档' },
  { key: 'completedThisMonth', queue: 'COMPLETED_MONTH', label: '本月归档' },
]
const rows = ref<AfterSalesCase[]>([]), loading = ref(false), saving = ref(false), error = ref('')
const detail = ref<AfterSalesCase | null>(null), detailVisible = ref(false), detailLoading = ref(false)
const tab = ref('case'), draftVisible = ref(false), draftId = ref<number | null>(null), contactLoading = ref(false)
function blankDraft(): CaseDraft { return { customerId: null, agentId: null, vehicleId: null, frameSn: '', productModelId: null, motorSn: '', batterySn: '', displaySn: '', purchaseDate: null, failureTime: null, faultType: '', faultPart: '', errorCode: '', description: '', attemptedRepair: '', warrantyStatus: 'PENDING', contact: null, endCustomerPurchaseDate: null } }
const draft = reactive<CaseDraft>(blankDraft())
const options = ref<CaseOptions>({ customers: [], agents: [], models: [] })
const solution = reactive({ technicalJudgment: '', solution: '', internalNote: '' })
const message = reactive<{ body: string; visibility: 'CUSTOMER' | 'INTERNAL' }>({ body: '', visibility: 'CUSTOMER' })
const shipment = reactive({ partName: '', quantity: 1, carrier: '', trackingNo: '', oldPartReturnRequired: false })
const dispatchVisible = ref(false), dispatchId = ref(0), dispatchForm = reactive({ carrier: '', trackingNo: '' })
const attachments = ref<CaseAttachment[]>([]), attachmentFiles = ref<File[]>([]), replyFiles = ref<File[]>([])
const attachmentVisibility = ref<'CUSTOMER' | 'INTERNAL'>('CUSTOMER'), attachmentCategory = ref('OTHER')
const attachmentInput = ref<HTMLInputElement>(), replyInput = ref<HTMLInputElement>(), pendingMessage = ref<CaseMessage | null>(null)
const attachmentTypes = '.jpg,.jpeg,.png,.pdf,.mp4,.txt,.log,.csv,.json'
const attachmentCategories: Record<string, string> = { FAULT_IMAGE: '故障图片', FAULT_VIDEO: '故障视频', FRAME_SN: '车架号照片', MOTOR_SN: '电机序列号照片', BATTERY_SN: '电池序列号照片', DISPLAY_SN: '仪表序列号照片', PURCHASE_PROOF: '购买凭证', DIAGNOSTIC: '诊断报告', TECHNICAL: '技术资料', OTHER: '其他附件' }
const faults = ['电机', '电池', '仪表', '控制器', '车架', '后三角', '前叉', '后避震', '刹车', '变速系统', '轮组', '其他']
const editable = computed(() => canWrite.value && ['DRAFT', 'NEEDS_INFO'].includes(detail.value?.status || ''))
const activeCase = computed(() => Boolean(detail.value && !['DRAFT', 'CLOSED', 'REJECTED'].includes(detail.value.status)))
const attachmentAllowed = computed(() => canMessage.value && Boolean(detail.value && !['CLOSED', 'REJECTED'].includes(detail.value.status)))
const shippingAllowed = computed(() => canShip.value && ['APPROVED', 'IN_PROGRESS', 'WAITING_PARTS', 'SHIPPED'].includes(detail.value?.status || ''))
const steps = ['待提交', '已提交', '审核中', '已审批', '处理中', '已发货', '完成']
const stepIndex = computed(() => ({ DRAFT: 0, SUBMITTED: 1, UNDER_REVIEW: 2, NEEDS_INFO: 2, APPROVED: 3, IN_PROGRESS: 4, WAITING_PARTS: 4, SHIPPED: 5, RESOLVED: 6, CLOSED: 7, REJECTED: 2 }[detail.value?.status || 'DRAFT']))
const historyNames: Record<string, string> = { RETURN_REQUEST: '提供旧件退回地址', RETURN_SEND: '客户寄回旧件', RETURN_RECEIVE: '仓库收到旧件', RETURN_INSPECT: '旧件检测', RETURN_SCRAP: '旧件报废', RETURN_COMPLETE: '完成旧件处理', RETURN_WAIVE: '豁免旧件退回', CASE_ASSIGNED: '分配售后负责人', CASE_CREATED: '创建案例草稿', CASE_UPDATED: '更新案例资料', CASE_SUBMITTED: '提交案例', REVIEW_START: '开始审核', REVIEW_APPROVE: '审批通过', REVIEW_REJECT: '拒绝案例', REVIEW_REQUEST_INFO: '要求补充资料', SOLUTION_SAVED: '保存维修方案', PROCESSING_STARTED: '开始处理', MESSAGE_ADDED: '客户可见留言', INTERNAL_MESSAGE_ADDED: '内部留言', ATTACHMENTS_ADDED: '上传案例附件', INTERNAL_ATTACHMENTS_ADDED: '上传内部附件', SHIPMENT_PLANNED: '登记待发货配件', SHIPMENT_DISPATCHED: '登记配件发货', CASE_RESOLVED: '标记已解决', CASE_CLOSED: '归档案例' }
const time = (value: string | null | undefined) => value ? value.replace('T', ' ').slice(0, 19) : '—'
const value = (text: unknown) => text === null || text === undefined || text === '' ? '—' : String(text)
function tone(s: CaseStatus) { return ['CLOSED', 'RESOLVED', 'APPROVED'].includes(s) ? 'success' : ['REJECTED'].includes(s) ? 'danger' : ['NEEDS_INFO', 'WAITING_PARTS'].includes(s) ? 'warning' : 'info' }

let listVersion = 0, detailVersion = 0
async function load() {
  if (!canRead.value) return
  const version = ++listVersion
  loading.value = true; error.value = ''
  try {
    const query = { ...filters }, search = keyword.value.trim()
    const [result, counts] = await Promise.all([afterSales.list(search, status.value, page.value, query), afterSales.dashboard(search, query)])
    if (version === listVersion) { rows.value = result.records; total.value = result.total; dashboard.value = counts }
  }
  catch (e) { if (version === listVersion) { error.value = afterSalesError(e); rows.value = []; total.value = 0; dashboard.value = null } }
  finally { if (version === listVersion) loading.value = false }
}
function filter() { page.value = 1; void load() }
function chooseQueue(queue: CaseQueue) { filters.queue = queue; status.value = ''; filter() }
function resetFilters() { Object.assign(filters, blankFilters()); keyword.value = ''; status.value = ''; filter() }
async function open(id: number) {
  const version = ++detailVersion
  detailVisible.value = true; detailLoading.value = true; detail.value = null; tab.value = 'case'
  attachmentFiles.value = []; replyFiles.value = []; pendingMessage.value = null; attachments.value = []
  try { const [current, files] = await Promise.all([afterSales.get(id), afterSales.attachments(id)]); if (version === detailVersion) { setDetail(current); attachments.value = files } }
  catch (e) { ElMessage.error(afterSalesError(e)); if (version === detailVersion) detailVisible.value = false }
  finally { if (version === detailVersion) detailLoading.value = false }
}
function setDetail(current: AfterSalesCase) { detail.value = current; Object.assign(solution, { technicalJudgment: current.technicalJudgment || '', solution: current.solution || '', internalNote: current.internalNote || '' }) }
async function returnsChanged() { if (!detail.value) return; try { setDetail(await afterSales.get(detail.value.id)); await load() } catch (e) { ElMessage.error(afterSalesError(e)) } }
async function loadOptions() { try { options.value = await afterSales.options(); return true } catch (e) { ElMessage.error(afterSalesError(e)); return false } }
async function newDraft() { if (!await loadOptions()) return; contactVersion++; contactLoading.value=false; draftId.value = null; attachmentFiles.value = []; replyFiles.value = []; pendingMessage.value = null; Object.assign(draft, blankDraft()); draft.contact=blankContact(); draftVisible.value = true; if(isAgent.value && props.authContext?.agentId) await fillCompanyContact(props.authContext.agentId) }
let contactVersion = 0
async function fillCompanyContact(id: number | null) {
  const version = ++contactVersion
  draft.contact=blankContact();contactLoading.value=Boolean(id);if(!id)return
  try { const company=await afterSalesCompanies.get(id);if(version!==contactVersion)return;const contact=blankContact();for(const key of Object.keys(contact) as (keyof CompanyContact)[])contact[key]=company[key]||'';draft.contact=contact }
  catch(e){ElMessage.error(afterSalesError(e));if(version===contactVersion)draft.contact=blankContact()}
  finally {if(version===contactVersion)contactLoading.value=false}
}
async function editDraft() {
  if (!detail.value) return
  if (!await loadOptions()) return
  const current = detail.value; contactVersion++; contactLoading.value=false; draftId.value = current.id
  for (const key of Object.keys(blankDraft()) as (keyof CaseDraft)[]) Object.assign(draft, { [key]: current[key] ?? blankDraft()[key] })
  draft.contact=blankContact();for(const key of Object.keys(draft.contact) as (keyof CompanyContact)[])draft.contact[key]=current[key]||''
  if (isAgent.value) draft.warrantyStatus = 'PENDING'
  draftVisible.value = true
}
async function saveDraft() {
  if (contactLoading.value) { ElMessage.warning('请等待公司联系资料加载完成'); return }
  if (!draft.description.trim()) { ElMessage.warning('请填写问题描述'); return }
  if (!isAgent.value && !draft.customerId && !draft.agentId) { ElMessage.warning('请选择客户或代理商归属'); return }
  saving.value = true
  try {
    const payload = { ...draft, agentId: isAgent.value ? null : draft.agentId || null, customerId: isAgent.value && !draft.vehicleId ? null : draft.customerId || null,
      productModelId: draft.productModelId || null, purchaseDate: draft.purchaseDate || null, failureTime: draft.failureTime || null }
    const result = draftId.value ? await afterSales.update(draftId.value, payload) : await afterSales.create(payload)
    draftVisible.value = false; detailVisible.value = true; tab.value = 'case'; setDetail(result); attachments.value = await afterSales.attachments(result.id); await load(); ElMessage.success('草稿已保存')
  } catch (e) { ElMessage.error(afterSalesError(e)) } finally { saving.value = false }
}
async function perform(action: () => Promise<unknown>, success: string) {
  if (!detail.value || saving.value) return
  const id = detail.value.id; saving.value = true
  try { await action(); const [current, files] = await Promise.all([afterSales.get(id), afterSales.attachments(id)]); setDetail(current); attachments.value = files; await load(); ElMessage.success(success) }
  catch (e) { ElMessage.error(afterSalesError(e)); if (pendingMessage.value) { try { setDetail(await afterSales.get(id)); attachments.value = await afterSales.attachments(id) } catch { /* Keep the upload retry state when refresh also fails. */ } } } finally { saving.value = false }
}
async function submitCase() {
  if (!detail.value) return
  if (!detail.value.frameSn?.trim() || !detail.value.faultType?.trim()) { ElMessage.warning('提交前请补充车架号和故障类型'); editDraft(); return }
  try { await ElMessageBox.confirm('提交后进入售后审核流程，资料暂时不可编辑。', '提交售后案例', { type: 'info' }); await perform(() => afterSales.submit(detail.value!.id), '案例已提交') } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(afterSalesError(e)) }
}
async function review(action: 'START' | 'APPROVE' | 'REJECT' | 'REQUEST_INFO') {
  if (!detail.value) return
  let reason = ''
  try {
    if (action === 'REJECT' || action === 'REQUEST_INFO') {
      const result = await ElMessageBox.prompt('请填写原因，客户将看到这段说明。', action === 'REJECT' ? '拒绝案例' : '要求补充资料', { inputType: 'textarea', inputValidator: input => !!input?.trim() && input.trim().length <= 1000 || '原因须为 1–1000 字' })
      reason = result.value.trim()
    } else if (action === 'APPROVE') await ElMessageBox.confirm('确认当前维修方案后，将进入已审批状态。', '审批通过', { type: 'warning' })
    await perform(() => afterSales.review(detail.value!.id, action, reason), '审核状态已更新')
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(afterSalesError(e)) }
}
async function sendMessage() {
  if (!pendingMessage.value && !message.body.trim()) { ElMessage.warning('请输入留言'); return }
  if (pendingMessage.value && !replyFiles.value.length) { ElMessage.warning('请选择需要补传的附件，或取消附件上传'); return }
  const success = replyFiles.value.length ? '留言已发送，附件已保存' : '留言已发送'
  await perform(async () => {
    const sent = pendingMessage.value || await afterSales.message(detail.value!.id, message.body.trim(), canInternal.value ? message.visibility : 'CUSTOMER')
    message.body = ''
    if (replyFiles.value.length) {
      pendingMessage.value = sent
      await afterSales.upload(detail.value!.id, replyFiles.value, sent.visibility, 'DIAGNOSTIC', sent.id)
    }
    pendingMessage.value = null; replyFiles.value = []; if (replyInput.value) replyInput.value.value = ''
  }, success)
}
function cancelMessageAttachments() {
  pendingMessage.value = null; replyFiles.value = []; message.body = ''
  if (replyInput.value) replyInput.value.value = ''
}
function selectFiles(event: Event, reply = false) {
  if (reply) replyFiles.value = []; else attachmentFiles.value = []
  const selected = Array.from((event.target as HTMLInputElement).files || [])
  if (selected.length > 5 || selected.reduce((total, file) => total + file.size, 0) > 150 * 1024 * 1024) { ElMessage.warning('每次最多5个文件，总大小最多150MB'); (event.target as HTMLInputElement).value = ''; return }
  for (const file of selected) {
    const isVideo = file.name.toLowerCase().endsWith('.mp4')
    if (!file.size || file.size > (isVideo ? 50 : 10) * 1024 * 1024) { ElMessage.warning('文件不能为空；视频最大50MB，其余文件最大10MB'); (event.target as HTMLInputElement).value = ''; return }
  }
  if (reply) replyFiles.value = selected; else attachmentFiles.value = selected
}
async function uploadAttachments() {
  if (!attachmentFiles.value.length) return
  await perform(async () => { await afterSales.upload(detail.value!.id, attachmentFiles.value, canInternal.value ? attachmentVisibility.value : 'CUSTOMER', attachmentCategory.value); attachmentFiles.value = []; if (attachmentInput.value) attachmentInput.value.value = '' }, '附件已上传')
}
async function downloadAttachment(item: CaseAttachment) {
  if (!detail.value) return
  try { const blob = await afterSales.download(detail.value.id, item.id); const url = URL.createObjectURL(blob); const anchor = document.createElement('a'); anchor.href = url; anchor.download = item.fileName; document.body.appendChild(anchor); anchor.click(); anchor.remove(); window.setTimeout(() => URL.revokeObjectURL(url), 30000) }
  catch (e) { ElMessage.error(afterSalesError(e)) }
}
const fileSize = (bytes: number) => bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.ceil(bytes / 1024))} KB`
async function saveSolution() { if (!solution.solution.trim()) { ElMessage.warning('请填写维修方案'); return }; await perform(() => afterSales.solution(detail.value!.id, { ...solution }), '维修方案已保存，等待审批') }
async function addShipment() {
  if (!shipment.partName.trim() || shipment.quantity < 1 || (shipment.trackingNo.trim() && !shipment.carrier.trim())) { ElMessage.warning('请填写配件和数量；实际发货还须填写承运商'); return }
  await perform(async () => { await afterSales.shipment(detail.value!.id, { ...shipment }); Object.assign(shipment, { partName: '', quantity: 1, carrier: '', trackingNo: '', oldPartReturnRequired: false }) }, '补发记录已保存')
}
function openDispatch(item: CaseShipment) { dispatchId.value = item.id; Object.assign(dispatchForm, { carrier: item.carrier || '', trackingNo: '' }); dispatchVisible.value = true }
async function dispatch() {
  if (!dispatchForm.carrier.trim() || !dispatchForm.trackingNo.trim()) { ElMessage.warning('请填写承运商和运单号'); return }
  await perform(async () => { await afterSales.dispatch(detail.value!.id, dispatchId.value, dispatchForm.carrier.trim(), dispatchForm.trackingNo.trim()); dispatchVisible.value = false }, '配件发货已登记')
}
async function finish(close: boolean) {
  try { await ElMessageBox.confirm(close ? '归档后案例将不能继续编辑或留言。' : '请确认售后问题已解决，且没有待发货配件。', close ? '归档案例' : '标记已解决', { type: 'warning' }); await perform(() => close ? afterSales.close(detail.value!.id) : afterSales.resolve(detail.value!.id), close ? '案例已归档' : '案例已解决') }
  catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(afterSalesError(e)) }
}
async function openAssignment(){if(!detail.value||!canAssign.value)return;const id=detail.value.id,version=detailVersion,username=props.authContext?.username;try{const options=await afterSalesCompanies.staff();if(version!==detailVersion||username!==props.authContext?.username||!detailVisible.value||detail.value?.id!==id||!canAssign.value)return;assignmentOptions.value=options.assignees;assignee.value=detail.value.assigneeId;assignmentVisible.value=true}catch(e){if(version===detailVersion)ElMessage.error(afterSalesError(e))}}
async function saveAssignment(){await perform(async()=>{await afterSalesCompanies.assign(detail.value!.id,assignee.value);assignmentVisible.value=false},'售后负责人已更新')}
watch(()=>draft.agentId,id=>{if(draftVisible.value&&!draftId.value&&!isAgent.value)void fillCompanyContact(id)})
onMounted(load)
onUnmounted(() => { listVersion++; detailVersion++; contactVersion++ })
watch(draftVisible, visible => { if (!visible) {contactVersion++;contactLoading.value=false} })
watch(detailVisible, visible=>{if(!visible){detailVersion++;assignmentVisible.value=false;assignmentOptions.value=[]}})
watch(() => props.authContext?.username, () => { listVersion++; detailVersion++; contactVersion++; dashboard.value = null; Object.assign(filters, blankFilters()); keyword.value = ''; status.value = ''; total.value = 0; rows.value = []; detail.value = null; detailVisible.value = false; draftVisible.value = false; companyVisible.value = false; assignmentVisible.value = false; dispatchVisible.value = false; assignmentOptions.value = []; options.value = {customers: [], agents: [], models: []}; Object.assign(draft, blankDraft()); attachmentFiles.value = []; replyFiles.value = []; pendingMessage.value = null; attachments.value = []; page.value = 1; void load() })
</script>

<template>
  <section class="after-sales" aria-label="售后案例工作台">
    <el-alert v-if="!canRead" title="当前账号没有售后案例访问权限" type="warning" :closable="false" />
    <template v-else>
      <div class="section-heading"><div><h2>售后案例</h2><p>{{ isAgent ? '申报故障、补充资料，查看维修及配件发货进度。' : '审核故障、确认维修方案，跟进配件发货与问题解决。' }}</p></div><div><el-button @click="companyVisible=true">公司联系资料</el-button><el-button v-if="canWrite" type="primary" @click="newDraft">新建案例</el-button></div></div>
      <div class="dashboard" aria-label="售后概况" :aria-busy="loading">
        <button v-for="card in dashboardCards" :key="card.key" type="button" class="dashboard-card" :class="{ selected: filters.queue === card.queue && !status }" :aria-pressed="filters.queue === card.queue && !status" :disabled="loading || !dashboard" @click="chooseQueue(card.queue)">
          <span>{{ card.label }}</span><strong>{{ dashboard ? dashboard[card.key] : '—' }}</strong>
        </button>
      </div>
      <p class="dashboard-help">统计限当前账号可见的公司，并随搜索条件更新；点击数字查看对应案例。待发货按实际待发配件统计；本月归档按服务器当月归档时间统计。</p>
      <form class="filters" @submit.prevent="filter"><el-input v-model="keyword" aria-label="案例搜索" placeholder="案例编号、车架号、问题、公司或联系人" clearable /><el-select v-model="status" aria-label="案例状态" placeholder="全部状态" clearable @change="filters.queue = ''"><el-option v-for="(label, key) in caseStatuses" :key="key" :label="label" :value="key" /></el-select><el-button native-type="submit" :loading="loading">查询</el-button><el-button :aria-expanded="advanced" @click="advanced = !advanced">高级筛选</el-button><el-button @click="resetFilters">重置</el-button><el-button @click="load">刷新</el-button></form>
      <el-form v-if="advanced" class="advanced-filters form-grid" label-position="top" @submit.prevent="filter">
        <el-form-item label="客户 / 公司名称"><el-input v-model="filters.companyName" maxlength="255" clearable /></el-form-item>
        <el-form-item label="联系人"><el-input v-model="filters.contactName" maxlength="100" clearable /></el-form-item>
        <el-form-item label="车架号"><el-input v-model="filters.frameSn" maxlength="128" clearable /></el-form-item>
        <el-form-item label="产品型号"><el-input v-model="filters.modelName" maxlength="100" clearable /></el-form-item>
        <el-form-item label="故障类型"><el-select v-model="filters.faultType" filterable allow-create clearable placeholder="全部故障类型"><el-option v-for="fault in faults" :key="fault" :value="fault" :label="fault" /></el-select></el-form-item>
        <el-form-item label="国家代码"><el-input v-model="filters.countryCode" maxlength="2" placeholder="例如 GB、FR、CN" clearable /></el-form-item>
        <el-form-item label="售后负责人"><el-input v-model="filters.assigneeName" maxlength="100" clearable /></el-form-item>
        <el-form-item label="创建起始日期"><el-date-picker v-model="filters.createdFrom" type="date" value-format="YYYY-MM-DD" placeholder="不限" /></el-form-item>
        <el-form-item label="创建结束日期（含当日）"><el-date-picker v-model="filters.createdTo" type="date" value-format="YYYY-MM-DD" placeholder="不限" /></el-form-item>
        <el-form-item><el-button type="primary" native-type="submit" :loading="loading">应用筛选</el-button></el-form-item>
      </el-form>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <el-table :data="rows" v-loading="loading" stripe empty-text="暂无符合条件的售后案例" @row-dblclick="(row: AfterSalesCase) => open(row.id)">
        <el-table-column prop="caseNo" label="案例编号" min-width="225"><template #default="scope"><el-button link type="primary" @click="open(scope.row.id)">{{ scope.row.caseNo }}</el-button></template></el-table-column>
        <el-table-column label="客户 / 代理商" min-width="150"><template #default="scope">{{ scope.row.customerName || scope.row.agentName || '—' }}</template></el-table-column>
        <el-table-column prop="frameSn" label="车架号" min-width="140" /><el-table-column prop="modelName" label="车型" min-width="115" /><el-table-column prop="faultType" label="故障类型" min-width="100" />
        <el-table-column label="状态" min-width="130"><template #default="scope"><el-tag :type="tone(scope.row.status)">{{ caseStatuses[scope.row.status as CaseStatus] }}</el-tag></template></el-table-column>
        <el-table-column label="保修状态" min-width="110"><template #default="scope">{{ warrantyNames[scope.row.warrantyStatus] || scope.row.warrantyStatus }}</template></el-table-column>
        <el-table-column prop="assigneeName" label="售后负责人" min-width="140"/>
        <el-table-column prop="contactName" label="联系人" min-width="120"/>
        <el-table-column prop="countryCode" label="国家" width="80"/>
        <el-table-column label="创建时间" min-width="170"><template #default="scope">{{ time(scope.row.createdAt) }}</template></el-table-column>
        <el-table-column label="更新时间" min-width="170"><template #default="scope">{{ time(scope.row.updatedAt) }}</template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @current-change="load" />
    </template>

    <el-dialog v-model="detailVisible" title="售后案例详情" width="min(1140px, 96vw)" destroy-on-close :close-on-click-modal="false">
      <div v-loading="detailLoading" class="detail-shell">
        <template v-if="detail">
          <div class="detail-heading"><div><strong>{{ detail.caseNo }}</strong><small>创建 {{ time(detail.createdAt) }} · 更新 {{ time(detail.updatedAt) }}</small></div><el-tag :type="tone(detail.status)">{{ caseStatuses[detail.status] }}</el-tag></div>
          <el-steps :active="stepIndex" finish-status="success" :process-status="detail.status === 'REJECTED' ? 'error' : 'process'" align-center><el-step v-for="item in steps" :key="item" :title="item" /></el-steps>
          <el-alert v-if="detail.rejectionReason" :title="detail.rejectionReason" :type="detail.status === 'REJECTED' ? 'error' : 'warning'" :closable="false" show-icon />
          <div class="actions">
            <el-button v-if="canAssign && !['CLOSED','REJECTED'].includes(detail.status)" :disabled="saving" @click="openAssignment">分配负责人</el-button>
            <el-button v-if="editable" :disabled="saving" @click="editDraft">编辑资料</el-button><el-button v-if="editable" type="primary" :loading="saving" @click="submitCase">提交案例</el-button>
            <el-button v-if="canReview && detail.status === 'SUBMITTED'" type="primary" :loading="saving" @click="review('START')">开始审核</el-button>
            <el-button v-if="canReview && detail.status === 'UNDER_REVIEW'" type="success" :loading="saving" :disabled="!detail.solution" @click="review('APPROVE')">审批通过</el-button>
            <el-button v-if="(canReview || canTech) && ['SUBMITTED', 'UNDER_REVIEW'].includes(detail.status)" :disabled="saving" @click="review('REQUEST_INFO')">要求补充资料</el-button>
            <el-button v-if="canReview && ['SUBMITTED', 'UNDER_REVIEW'].includes(detail.status)" type="danger" plain :disabled="saving" @click="review('REJECT')">拒绝</el-button>
            <el-button v-if="(canReview || canTech) && detail.status === 'APPROVED'" type="primary" :loading="saving" @click="perform(() => afterSales.process(detail!.id), '已开始处理')">开始处理</el-button>
            <el-button v-if="canTech && ['IN_PROGRESS', 'SHIPPED'].includes(detail.status)" type="success" :disabled="saving || detail.shipments.some(s => s.status === 'PLANNED')" @click="finish(false)">标记已解决</el-button>
            <el-button v-if="canWrite && detail.status === 'RESOLVED'" type="success" :disabled="saving" @click="finish(true)">确认并归档</el-button>
          </div>
          <el-tabs v-model="tab">
            <el-tab-pane label="联系资料" name="contact"><p class="file-help">这里保存本案例的联系资料。公司资料更新不会改变历史案例中的联系人和地址。{{detail.snapshotSource==='MIGRATED_CURRENT'?'此旧案例的基础联系信息来自升级时可确定的主数据。':''}}</p><dl class="facts"><div><dt>公司</dt><dd>{{value(detail.companyName)}}</dd></div><div><dt>售后负责人</dt><dd>{{value(detail.assigneeName)}}</dd></div><div><dt>联系人</dt><dd>{{value(detail.contactName)}}</dd></div><div><dt>电话</dt><dd>{{value(detail.contactPhone)}}</dd></div><div><dt>Email</dt><dd>{{value(detail.contactEmail)}}</dd></div><div><dt>国家 / 省州 / 城市</dt><dd>{{value(detail.countryCode)}} / {{value(detail.region)}} / {{value(detail.city)}}</dd></div><div><dt>详细地址</dt><dd>{{value(detail.addressLine)}}</dd></div><div><dt>邮编</dt><dd>{{value(detail.postalCode)}}</dd></div><div><dt>最终客户购买日期</dt><dd>{{value(detail.endCustomerPurchaseDate)}}</dd></div></dl></el-tab-pane>
            <el-tab-pane label="工单信息" name="case"><dl class="facts"><div><dt>客户</dt><dd>{{ value(detail.customerName) }}</dd></div><div><dt>代理商</dt><dd>{{ value(detail.agentName) }}</dd></div><div><dt>车型</dt><dd>{{ value(detail.modelName) }}</dd></div><div><dt>车架号</dt><dd>{{ value(detail.frameSn) }}</dd></div><div><dt>电机序列号</dt><dd>{{ value(detail.motorSn) }}</dd></div><div><dt>电池序列号</dt><dd>{{ value(detail.batterySn) }}</dd></div><div><dt>仪表序列号</dt><dd>{{ value(detail.displaySn) }}</dd></div><div><dt>购买日期</dt><dd>{{ value(detail.purchaseDate) }}</dd></div><div><dt>故障发生时间</dt><dd>{{ time(detail.failureTime) }}</dd></div><div><dt>故障类型 / 部件</dt><dd>{{ value(detail.faultType) }} / {{ value(detail.faultPart) }}</dd></div><div><dt>故障代码</dt><dd>{{ value(detail.errorCode) }}</dd></div><div><dt>保修状态</dt><dd>{{ warrantyNames[detail.warrantyStatus] || '待确认' }}</dd></div><div class="wide"><dt>问题描述</dt><dd>{{ detail.description }}</dd></div><div class="wide"><dt>已尝试维修方法</dt><dd>{{ value(detail.attemptedRepair) }}</dd></div></dl></el-tab-pane>
            <el-tab-pane label="维修方案" name="solution"><el-form v-if="canTech && detail.status === 'UNDER_REVIEW'" label-position="top"><el-form-item label="技术判断"><el-input v-model="solution.technicalJudgment" type="textarea" :rows="3" maxlength="4000" show-word-limit /></el-form-item><el-form-item label="维修方案" required><el-input v-model="solution.solution" type="textarea" :rows="5" maxlength="4000" show-word-limit /></el-form-item><el-form-item label="内部备注（客户不可见）"><el-input v-model="solution.internalNote" type="textarea" :rows="3" maxlength="4000" show-word-limit /></el-form-item><el-button type="primary" :loading="saving" @click="saveSolution">保存维修方案</el-button></el-form><dl v-else class="facts"><div class="wide"><dt>技术判断</dt><dd>{{ value(detail.technicalJudgment) }}</dd></div><div class="wide"><dt>维修方案</dt><dd>{{ value(detail.solution) }}</dd></div><div v-if="canInternal" class="wide"><dt>内部备注（客户不可见）</dt><dd>{{ value(detail.internalNote) }}</dd></div></dl></el-tab-pane>
            <el-tab-pane label="配件与发货" name="shipments"><el-table :data="detail.shipments" empty-text="暂无补发记录"><el-table-column prop="partName" label="补发配件" min-width="140" /><el-table-column prop="quantity" label="数量" width="70" /><el-table-column label="状态" width="100"><template #default="scope">{{ scope.row.status === 'SHIPPED' ? '已发货' : '待发货' }}</template></el-table-column><el-table-column prop="carrier" label="承运商" min-width="100" /><el-table-column prop="trackingNo" label="运单号" min-width="150" /><el-table-column label="发货时间" min-width="170"><template #default="scope">{{ time(scope.row.shippedAt) }}</template></el-table-column><el-table-column label="旧件退回" width="100"><template #default="scope">{{ scope.row.oldPartReturnRequired ? '需退回' : '无需退回' }}</template></el-table-column><el-table-column v-if="shippingAllowed" label="操作" width="110"><template #default="scope"><el-button v-if="scope.row.status === 'PLANNED'" link type="primary" :disabled="saving" @click="openDispatch(scope.row)">登记发货</el-button></template></el-table-column></el-table>
              <el-form v-if="shippingAllowed" class="sub-form" label-position="top"><h3>登记补发配件</h3><div class="form-grid"><el-form-item label="配件名称" required><el-input v-model="shipment.partName" maxlength="255" /></el-form-item><el-form-item label="数量" required><el-input-number v-model="shipment.quantity" :min="1" :max="1000000" /></el-form-item><el-form-item label="承运商"><el-input v-model="shipment.carrier" maxlength="128" /></el-form-item><el-form-item label="运单号（已发货时填写）"><el-input v-model="shipment.trackingNo" maxlength="128" /></el-form-item></div><el-checkbox v-model="shipment.oldPartReturnRequired">需要退回旧件</el-checkbox><div class="actions"><el-button type="primary" :loading="saving" @click="addShipment">保存补发记录</el-button></div></el-form>
            </el-tab-pane>
            <el-tab-pane label="旧件处理" name="returns" lazy><AfterSalesReturns :key="detail.id" :case-id="detail.id" :status="detail.status" :is-agent="isAgent" :can-write="canWrite" :can-ship="canShip" :can-tech="canTech" :can-review="canReview" @changed="returnsChanged" /></el-tab-pane>
            <el-tab-pane :label="`附件（${attachments.length}）`" name="attachments">
              <p class="file-help">支持 JPG、PNG、PDF、MP4（H.264）、UTF-8 文本及 JSON 诊断文件。视频最大50MB，其余最大10MB；每批最多5个、合计150MB。</p>
              <el-table :data="attachments" empty-text="暂无附件">
                <el-table-column label="文件" min-width="190"><template #default="scope"><el-button link type="primary" @click="downloadAttachment(scope.row)">{{ scope.row.fileName }}</el-button></template></el-table-column>
                <el-table-column label="用途" min-width="125"><template #default="scope">{{ attachmentCategories[scope.row.category] || '其他附件' }}</template></el-table-column>
                <el-table-column label="大小" width="90"><template #default="scope">{{ fileSize(scope.row.fileSize) }}</template></el-table-column>
                <el-table-column label="可见范围" width="110"><template #default="scope">{{ scope.row.visibility === 'INTERNAL' ? '仅内部' : '客户可见' }}</template></el-table-column>
                <el-table-column label="来源" width="110"><template #default="scope">{{ scope.row.messageId ? '沟通留言' : '案例资料' }}</template></el-table-column>
                <el-table-column label="上传时间" min-width="170"><template #default="scope">{{ time(scope.row.createdAt) }}</template></el-table-column>
                <el-table-column label="操作" width="90"><template #default="scope"><el-button link type="primary" @click="downloadAttachment(scope.row)">下载</el-button></template></el-table-column>
              </el-table>
              <el-form v-if="attachmentAllowed" class="sub-form" label-position="top">
                <div class="form-grid"><el-form-item label="附件用途"><el-select v-model="attachmentCategory"><el-option v-for="(label, key) in attachmentCategories" :key="key" :value="key" :label="label" /></el-select></el-form-item><el-form-item v-if="canInternal" label="可见范围"><el-radio-group v-model="attachmentVisibility"><el-radio value="CUSTOMER">客户可见</el-radio><el-radio value="INTERNAL">仅内部</el-radio></el-radio-group></el-form-item></div>
                <el-form-item label="选择附件（可多选）"><input ref="attachmentInput" type="file" multiple :accept="attachmentTypes" :disabled="saving" aria-label="选择案例附件" @change="selectFiles($event)" /></el-form-item>
                <ul v-if="attachmentFiles.length" class="file-selection"><li v-for="(file, index) in attachmentFiles" :key="index">{{ file.name }} · {{ fileSize(file.size) }}</li></ul>
                <el-button type="primary" :disabled="!attachmentFiles.length" :loading="saving" @click="uploadAttachments">上传附件</el-button>
              </el-form>
            </el-tab-pane>
            <el-tab-pane label="沟通记录" name="messages">
              <el-empty v-if="!detail.messages.length" description="暂无留言" :image-size="65" />
              <article v-for="item in detail.messages" :key="item.id" class="message">
                <header><span>{{ item.visibility === 'INTERNAL' ? '内部留言' : '客户可见留言' }}</span><small>{{ time(item.createdAt) }} · 用户 {{ item.authorId || '—' }}</small></header><p>{{ item.body }}</p>
                <div class="message-files"><el-button v-for="file in attachments.filter(attachment => attachment.messageId === item.id)" :key="file.id" link type="primary" @click="downloadAttachment(file)">{{ file.fileName }}（{{ fileSize(file.fileSize) }}）</el-button></div>
              </article>
              <el-form v-if="canMessage && activeCase" class="sub-form" label-position="top">
                <el-alert v-if="pendingMessage" title="留言已发送，附件尚未完成。可重试上传附件，不会重复发送留言。" type="warning" :closable="false" />
                <el-form-item label="留言内容"><el-input v-model="message.body" type="textarea" :rows="4" maxlength="4000" show-word-limit :disabled="Boolean(pendingMessage)" /></el-form-item>
                <el-radio-group v-if="canInternal" v-model="message.visibility" :disabled="Boolean(pendingMessage)"><el-radio value="CUSTOMER">客户可见</el-radio><el-radio value="INTERNAL">仅内部</el-radio></el-radio-group>
                <el-form-item label="随留言上传附件（可多选）"><input ref="replyInput" type="file" multiple :accept="attachmentTypes" :disabled="saving" aria-label="选择留言附件" @change="selectFiles($event, true)" /></el-form-item>
                <ul v-if="replyFiles.length" class="file-selection"><li v-for="(file, index) in replyFiles" :key="index">{{ file.name }} · {{ fileSize(file.size) }}</li></ul>
                <div class="actions"><el-button type="primary" :loading="saving" @click="sendMessage">{{ pendingMessage ? '重试附件上传' : '发送留言' }}</el-button><el-button v-if="pendingMessage" :disabled="saving" @click="cancelMessageAttachments">取消附件上传</el-button></div>
              </el-form>
            </el-tab-pane>
            <el-tab-pane label="变更记录" name="history"><el-timeline><el-timeline-item v-for="item in detail.history" :key="item.id" :timestamp="time(item.operationTime)"><strong>{{ historyNames[item.action] || item.action }}</strong><span class="history-operator">{{ item.operatorName }}</span></el-timeline-item></el-timeline></el-tab-pane>
          </el-tabs>
        </template>
      </div>
    </el-dialog>

    <el-dialog v-model="draftVisible" :title="draftId ? '编辑案例资料' : '新建售后案例'" width="min(860px, 96vw)" :close-on-click-modal="false" destroy-on-close>
      <el-form :model="draft" label-position="top"><template v-if="draft.contact"><h3>案例联系资料</h3><AfterSalesContactFields :model="draft.contact" :disabled="contactLoading||saving"/><el-alert v-if="contactLoading" title="正在加载公司联系资料，请稍候" type="info" :closable="false"/><p class="file-help">公司资料自动带出后，可在此纠正本次案例的联系人和地址。</p></template><div class="form-grid">
        <template v-if="!isAgent"><el-form-item label="客户"><el-select v-model="draft.customerId" filterable clearable placeholder="选择客户"><el-option v-for="item in options.customers" :key="item.id" :value="item.id" :label="item.name" /></el-select></el-form-item><el-form-item label="代理商"><el-select v-model="draft.agentId" filterable clearable placeholder="选择代理商" :disabled="Boolean(draftId)"><el-option v-for="item in options.agents" :key="item.id" :value="item.id" :label="item.name" /></el-select></el-form-item></template>
        <el-form-item label="车架号（提交时必填）"><el-input v-model="draft.frameSn" maxlength="128" /></el-form-item>
        <el-form-item label="最终客户购买日期"><el-date-picker v-model="draft.endCustomerPurchaseDate" type="date" value-format="YYYY-MM-DD"/></el-form-item>
        <el-form-item label="产品型号"><el-select v-model="draft.productModelId" filterable clearable placeholder="选择产品型号"><el-option v-for="item in options.models" :key="item.id" :value="item.id" :label="item.name" /></el-select></el-form-item><el-form-item label="购买日期"><el-date-picker v-model="draft.purchaseDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="电机序列号"><el-input v-model="draft.motorSn" maxlength="128" /></el-form-item><el-form-item label="电池序列号"><el-input v-model="draft.batterySn" maxlength="128" /></el-form-item><el-form-item label="仪表序列号"><el-input v-model="draft.displaySn" maxlength="128" /></el-form-item><el-form-item label="故障类型（提交时必填）"><el-select v-model="draft.faultType" filterable allow-create default-first-option><el-option v-for="fault in faults" :key="fault" :label="fault" :value="fault" /></el-select></el-form-item>
        <el-form-item label="故障部件"><el-input v-model="draft.faultPart" maxlength="128" /></el-form-item><el-form-item label="故障代码"><el-input v-model="draft.errorCode" maxlength="128" /></el-form-item><el-form-item label="故障发生时间"><el-date-picker v-model="draft.failureTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item><el-form-item v-if="!isAgent" label="保修状态"><el-select v-model="draft.warrantyStatus"><el-option v-for="(label, key) in warrantyNames" :key="key" :label="label" :value="key" /></el-select></el-form-item>
      </div><el-form-item label="问题描述" required><el-input v-model="draft.description" type="textarea" :rows="4" maxlength="5000" show-word-limit /></el-form-item><el-form-item label="已尝试维修方法"><el-input v-model="draft.attemptedRepair" type="textarea" :rows="3" maxlength="2000" show-word-limit /></el-form-item></el-form>
      <template #footer><el-button :disabled="saving" @click="draftVisible = false">取消</el-button><el-button type="primary" :loading="saving||contactLoading" :disabled="contactLoading" @click="saveDraft">保存草稿</el-button></template>
    </el-dialog>
    <el-dialog v-model="companyVisible" title="售后公司资料" width="min(1050px,96vw)" destroy-on-close><AfterSalesCompanyWorkspace :auth-context="authContext"/></el-dialog>
    <el-dialog v-if="canAssign" v-model="assignmentVisible" title="分配售后负责人" width="min(480px,95vw)" :close-on-click-modal="false"><el-select v-model="assignee" filterable clearable placeholder="选择售后负责人（清空表示待分配）" style="width:100%"><el-option v-for="item in assignmentOptions" :key="item.id" :value="item.id" :label="item.name"/></el-select><template #footer><el-button :disabled="saving" @click="assignmentVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveAssignment">保存负责人</el-button></template></el-dialog>
    <el-dialog v-model="dispatchVisible" title="登记配件发货" width="min(480px, 95vw)" :close-on-click-modal="false"><el-form label-position="top"><el-form-item label="承运商" required><el-input v-model="dispatchForm.carrier" maxlength="128" /></el-form-item><el-form-item label="运单号" required><el-input v-model="dispatchForm.trackingNo" maxlength="128" /></el-form-item></el-form><template #footer><el-button @click="dispatchVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="dispatch">确认发货</el-button></template></el-dialog>
  </section>
</template>

<style scoped>
.after-sales { display: grid; gap: 18px; padding: 22px; background: #fff; border: 1px solid #e4eaf0; border-radius: 12px; }
.section-heading, .detail-heading { display: flex; gap: 18px; justify-content: space-between; align-items: center; }
h2 { margin: 0; font-size: 22px; color: #213547; } h3 { margin: 0 0 15px; font-size: 16px; } p { color: #617080; line-height: 1.6; } .section-heading p { margin: 8px 0 0; }
.filters { display: flex; flex-wrap: wrap; gap: 10px; }.filters > .el-input { flex: 1; min-width: 230px; }.filters > .el-select { width: 180px; }
.dashboard { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 10px; }
.dashboard-card { display: grid; gap: 10px; padding: 16px; text-align: left; border: 1px solid #dce5ee; border-radius: 9px; background: #f8fafc; color: #405468; cursor: pointer; font: inherit; }
.dashboard-card strong { color: #223c55; font-size: 26px; }.dashboard-card.selected { border-color: #2675bc; background: #edf6ff; }.dashboard-card:focus-visible { outline: 3px solid #2675bc; outline-offset: 2px; }.dashboard-card:disabled { cursor: default; opacity: .65; }
.dashboard-help { margin: -8px 0 0; font-size: 12px; }.advanced-filters { padding: 18px; border: 1px solid #e4eaf0; border-radius: 8px; }
.el-pagination { justify-content: flex-end; }.detail-shell { min-height: 180px; }.detail-heading { margin-bottom: 22px; }.detail-heading strong { display: block; overflow-wrap: anywhere; font-size: 17px; }.detail-heading small { display: block; color: #7b8794; margin-top: 7px; }.el-steps { margin: 16px 0 22px; }.actions { display: flex; flex-wrap: wrap; gap: 8px; margin: 18px 0; }.actions .el-button { margin-left: 0; }
.facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px 28px; margin: 18px 0; }.facts .wide { grid-column: 1 / -1; }dt { color: #75818d; font-size: 12px; margin-bottom: 6px; }dd { margin: 0; color: #273747; line-height: 1.7; white-space: pre-wrap; overflow-wrap: anywhere; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 18px; }.form-grid .el-input-number,.form-grid .el-select,.form-grid .el-date-editor { width: 100%; }.form-grid small { color: #7c8997; font-size: 12px; line-height: 1.6; }.sub-form { margin-top: 24px; padding-top: 20px; border-top: 1px solid #e8edf2; }.message { margin: 14px 0; border: 1px solid #e6ecf2; border-radius: 8px; padding: 15px; background: #fafcfd; }.message header { display: flex; justify-content: space-between; gap: 12px; font-size: 12px; }.message header span { font-weight: 600; }.message small,.history-operator { color: #7b8794; }.message p { margin-bottom: 0; white-space: pre-wrap; overflow-wrap: anywhere; color: #293a4a; }.history-operator { margin-left: 14px; }.el-timeline { padding-left: 8px; margin-top: 18px; }
.file-help { font-size: 12px; color: #748393; }.file-selection { font-size: 13px; color: #51687c; padding-left: 20px; line-height: 1.8; }.message-files { display: flex; gap: 10px; flex-wrap: wrap; margin-top: 10px; }.message-files .el-button { margin-left: 0; }
@media (max-width: 680px) { .after-sales { padding: 14px; }.section-heading { align-items: flex-start; }.facts,.form-grid { grid-template-columns: 1fr; }.detail-heading { align-items: flex-start; }.message header { flex-direction: column; }.el-steps :deep(.el-step__title) { font-size: 11px; }.filters > .el-select { flex: 1; }.el-pagination { justify-content: center; } }
</style>
