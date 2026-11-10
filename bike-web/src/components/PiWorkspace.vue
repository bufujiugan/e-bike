<script setup lang="ts">
import RecordIdColumn from './table/RecordIdColumn.vue'
import { isSelectablePiLayout } from '../pi-template-layouts'
import { createUuid } from '../utils/uuid'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { createPiLocalDraftStore, type DraftItem, type PiDraftForm, type PiLocalDraft, type PiLocalDraftContent } from '../pi-local-drafts'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { hasPermission, hasRole, type PermissionContext } from '../permissions'
import { piApi, reservationLabel, type Money, type PiCreate, type PiDocument, type PiOptions, type PiPayment, type PiView, type PiLockOption, type PiLogistics, type PiWorkflow, type PiWorkflowComplete } from '../pi-api'
import PiReceiptAttachments from './PiReceiptAttachments.vue'
import PiTemplates from './PiTemplates.vue'
import PiFulfillmentProfiles from './PiFulfillmentProfiles.vue'
import { fulfillmentApi, type FulfillmentProfile } from '../fulfillment-api'
import PiExportTask from './PiExportTask.vue'
import PiItemImage from './PiItemImage.vue'
import PiConfirmationAttachments from './PiConfirmationAttachments.vue'
import PiLogisticsAttachments from './PiLogisticsAttachments.vue'
import PiRevisions from './PiRevisions.vue'
import PiBusinessDashboard from './PiBusinessDashboard.vue'
import type { PiTemplate } from '../pi-api'
import { customerProfileApi } from '../customer-profile-api'
import PiTemplatePicker from './PiTemplatePicker.vue'
import PiWorkflowPanel from './PiWorkflowPanel.vue'
import '../styles/pi-draft.css'

const props = defineProps<{ context: (PermissionContext & { userType?: string; username?: string }) | null; initialKeyword?: string }>()
const emit = defineEmits<{ navigate: [view: string] }>()
const isAgent = computed(() => hasRole(props.context, 'WEB_AGENT') || props.context?.userType === 'AGENT')
const isPda = computed(() => hasRole(props.context, 'PDA_OPERATOR') || props.context?.userType === 'PDA_OPERATOR')
const internal = computed(() => !isAgent.value && !isPda.value)
const allowed = (permission: string) => internal.value && hasPermission(props.context, permission)
const canRead = computed(() => !isPda.value && hasPermission(props.context, 'PI_READ'))
const canWrite = computed(() => allowed('PI_WRITE'))
const canManageTemplates = computed(() => allowed('PI_TEMPLATE_MANAGE'))
const templateVisible = ref(false), profilesVisible = ref(false)
const fulfillmentProfiles = ref<FulfillmentProfile[]>([]), profilesError = ref('')
const requiresProfile = computed(() => editingId.value == null || Boolean(detail.value?.document?.business?.fulfillmentProfileId))
const draftProfile = computed(() => fulfillmentProfiles.value.find(p => p.id === draft.document.business?.fulfillmentProfileId))
const detailProfile = computed(() => fulfillmentProfiles.value.find(p => p.id === detail.value?.document?.business?.fulfillmentProfileId))
let profileSelectionVersion = 0
async function loadProfiles() {
  if (!internal.value) return
  profilesError.value = ''
  try { fulfillmentProfiles.value = await fulfillmentApi.list() } catch(e) { profilesError.value = message(e, '履约配置加载失败，请重试') }
}
async function chooseProfile(id?: number) {
  const selectionVersion = ++profileSelectionVersion, sessionId = localDraftId
  if (!draft.document.business) return
  const profile = fulfillmentProfiles.value.find(p => p.id === id)
  if (!profile) { draft.document.business.fulfillmentProfileId = null; draft.document.business.paymentDueDate = null; draft.document.business.collectionEntity = draft.tradeType === 'EXPORT' ? 'HONG_KONG' : 'GUANGTA'; return }
  optionsBusy.value = true; createError.value = ''
  try {
    let page = 1, found: PiTemplate | undefined
    while (!found) { const result = await piApi.templates(profile.tradeType, page++); found = result.records.find(t => t.templateCode === profile.templateCode && t.templateVersion === profile.templateVersion); if (found || !result.records.length || (page - 1) * 20 >= result.total) break }
    if (selectionVersion !== profileSelectionVersion || sessionId !== localDraftId || !createVisible.value) return
    if (!found) throw new Error('配置对应的模板未找到，请核对配置')
    draft.tradeType = profile.tradeType
    draft.document.business.fulfillmentProfileId = id
    draft.document.business.collectionEntity = profile.collectionEntity
    if (profile.rules.warehouseIds.length === 1) draft.document.business.warehouseId = profile.rules.warehouseIds[0]!
    if (profile.rules.paymentMode === 'CREDIT') { draft.depositRequiredAmount = '0.00'; draft.shipmentRequiredAmount = '0.00' }
    chooseTemplate(found)
  } catch(e) { if (selectionVersion === profileSelectionVersion && sessionId === localDraftId && createVisible.value) { createError.value = message(e, '选用配置失败'); draft.document.business.fulfillmentProfileId = null } }
  finally { if (selectionVersion === profileSelectionVersion && sessionId === localDraftId) optionsBusy.value = false }
}
const fixedDocumentFields = ['sellerName', 'sellerAddress', 'sellerContact', 'sellerCountry', 'sellerContactName', 'sellerPhone', 'sellerEmail', 'bankName', 'bankAccount', 'beneficiary', 'templateCode', 'templateVersion']
function chooseTemplate(value: PiTemplate) {
  if (value.tradeType !== draft.tradeType || !isSelectablePiLayout(value.layoutCode)) return
  for (const key of fixedDocumentFields as (keyof PiTemplate)[]) Object.assign(draft.document, { [key]: value[key] })
}
const canPay = computed(() => allowed('PI_PAYMENT_CONFIRM'))
const canReversePayment = computed(() => allowed('PI_PAYMENT_REVERSE'))
const canRelease = computed(() => allowed('PI_RELEASE'))
const canManageReservation = computed(() => allowed('ORDER_WRITE') && allowed('AGENT_LOCK_WRITE'))
const canLogistics = computed(() => allowed('PI_LOGISTICS_WRITE'))
const canAfterSales = computed(() => allowed('AFTER_SALES_READ'))
const logisticsAttachment = ref<PiLogistics | null>(null)
const logisticsAttachmentVisible = ref(false)
function openLogisticsAttachment(row: PiLogistics) { logisticsAttachment.value = row; logisticsAttachmentVisible.value = true }
const canExport = computed(() => hasPermission(props.context, 'PI_EXPORT'))
const reportRefresh = ref(0), exportBusy = ref(false)
const showFinance = computed(() => canPay.value || canRelease.value || canReversePayment.value)
const rows = ref<PiView[]>([]), total = ref(0), page = ref(1), pageSize = ref(20)
const keyword = ref(props.initialKeyword ?? ''), status = ref(''), busy = ref(false), loadError = ref('')
const detail = ref<PiView | null>(null), detailVisible = ref(false), detailBusy = ref(false), detailTab = ref('items')
const workflow = ref<PiWorkflow | null>(null), workflowBusy = ref(false), workflowError = ref(''), workflowActionBusy = ref(false)
const batchVisible = ref(false), batchBusy = ref(false), batchError = ref(''), batchVehicles = ref<Awaited<ReturnType<typeof piApi.batch>>['vehicles']>([])
async function openBatch(batchId: number) {
  if (!detail.value) return
  const piId = detail.value.id; batchVisible.value = true; batchBusy.value = true; batchError.value = ''; batchVehicles.value = []
  try { const response = await piApi.batch(piId,batchId); if (detail.value?.id === piId && batchVisible.value) batchVehicles.value = response.vehicles }
  catch (error) { batchError.value = message(error,'出库批次加载失败') }
  finally { batchBusy.value = false }
}
const actionBusy = ref(false), createVisible = ref(false), optionsBusy = ref(false)
const editingId = ref<number | null>(null)
const fromLocks = ref(false), lockBusy = ref(false), lockError = ref(''), lockPage = ref(1), lockTotal = ref(0), lockKeyword = ref('')
const lockRows = ref<PiLockOption[]>([]), selectedLocks = ref<Array<PiLockOption & { quantity: number }>>([])
let conversionKey = '', conversionFingerprint = '', lockVersion = 0
const fixedItems = computed(() => fromLocks.value || Boolean(draft.sourceOrderId) || Boolean(editingId.value && detail.value?.fulfillment?.length))
const hasBoundOrders = computed(() => Boolean(editingId.value && (detail.value?.sourceOrderId || detail.value?.fulfillment?.length)))
const editingRevision = computed(() => Boolean(editingId.value && detail.value?.previousRevisionId))
const revisionFulfillment = computed(() => editingRevision.value && Boolean(detail.value?.fulfillment?.length))
const options = ref<PiOptions>({ customers: [], agents: [], skus: [], orders: [] })
const optionsError = ref(''), createError = ref(''), operationError = ref('')
const optionKeyword = ref('')
const stateNames: Record<string, string> = { DRAFT: '草稿', CONFIRMED: '已确认', RELEASED: '已放行', CANCELLED: '已作废', SUPERSEDED: '历史版本' }
const paymentNames: Record<string, string> = { UNPAID: '未收款', PARTIAL: '部分到账', DEPOSIT_MET: '达到定金门槛', PAID: '全额到账' }
const formatDateTime = (value?: string | null) => value ? value.replace('T', ' ').slice(0, 19) : '—'
const states = computed(() => isAgent.value ? ['CONFIRMED', 'RELEASED'] : ['DRAFT', 'CONFIRMED', 'RELEASED', 'CANCELLED', 'SUPERSEDED'])
const documentLabels: [keyof PiDocument, string][] = [
  ['sellerName', '卖方名称'], ['sellerAddress', '卖方地址'], ['sellerContact', '卖方联系方式'],
  ['sellerContactName', '卖方联系人'], ['sellerPhone', '卖方电话'], ['sellerEmail', '卖方Email'],
  ['buyerAddress', '买方地址'], ['buyerContact', '买方联系方式'], ['beneficiary', '收款人'],
  ['bankName', '收款银行'], ['bankAccount', '收款账号'], ['paymentTerms', '付款条款'],
  ['deliveryTerms', '交付条款'], ['templateCode', '模板名称 / 编码'], ['templateVersion', '模板版本'],
  ['issueDate', '开单日期'], ['validUntil', '有效期'], ['shippingAddress', '收货地址'], ['transportMode', '运输方式'],
  ['tradeTerms', '贸易术语（外销）'], ['buyerCountry', '买方国家/地区'], ['sellerCountry', '卖方国家/地区'],
  ['buyerContactName', '买方联系人'], ['buyerPhone', '买方电话'], ['buyerEmail', '买方Email'], ['businessOwner', '业务归属'],
]
function emptyDocument(): PiDocument {
  return { business: { warehouseId: null, domesticShipment: null, collectionEntity: 'GUANGTA', orderSource: 'EXISTING_ORDER' }, sellerName: '', sellerAddress: '', sellerContact: '', buyerAddress: '', buyerContact: '', bankName: '', bankAccount: '', beneficiary: '', paymentTerms: '', deliveryTerms: '', templateCode: '', templateVersion: '' }
}
function newItem(): DraftItem { return { key: createUuid(), skuId: undefined, quantity: 1, unitPrice: '', discountAmount: '0.00', taxAmount: '0.00', unit: '台' } }
const draft = reactive<PiDraftForm>({ customerId: undefined, agentId: undefined, sourceOrderId: undefined, tradeType: 'DOMESTIC', currencyCode: 'CNY', plannedShipDate: '', discountAmount: '0.00', taxAmount: '0.00', freightAmount: '0.00', otherFeeAmount: '0.00', depositRequiredAmount: '', shipmentRequiredAmount: '', remark: '', document: emptyDocument(), items: [newItem()] })
const draftSteps = [
  { title: '客户与商品', description: '选客户，添加商品' },
  { title: '交易与交付', description: '选模板，填写约定' },
  { title: '核对保存', description: '核对后保存草稿' },
]
const draftStep = ref(0), templateRefreshKey = ref(0), draftScroll = ref<HTMLElement | null>(null)
const feesExpanded = ref(false)
const draftValidation = ref<{ step: number; field: string; message: string } | null>(null)
let draftBaseline = ''
const localDrafts = ref<PiLocalDraft[]>([]), localDraftError = ref(''), localSaveError = ref(''), localSavedAt = ref('')
const restoringDraft = ref(false), leaveDraftVisible = ref(false)
let localDraftId = '', localDraftOwner = '', activeLocalDraft: PiLocalDraft | null = null, lastLocalContent = ''
let initializingDraft = false, baseUpdatedAt: string | null = null
const localSaveStatus = computed(() => localSaveError.value ? '本机草稿保存失败' : localSavedAt.value ? '已自动保存到本机' : '填写后自动保存到本机')
function localStore(username = props.context?.username || '') { return createPiLocalDraftStore(window.localStorage, username) }
function refreshLocalDrafts() {
  if (!canWrite.value || !props.context?.username) { localDrafts.value = []; return }
  try { const result = localStore().list(); localDrafts.value = result.records; localDraftError.value = result.errors.join('；') }
  catch { localDraftError.value = '无法读取本机草稿，请检查浏览器存储设置' }
}
function beginLocalDraft() {
  localDraftId = createUuid(); localDraftOwner = props.context?.username || ''
  activeLocalDraft = null; lastLocalContent = ''; localSavedAt.value = ''; localSaveError.value = ''
  baseUpdatedAt = editingId.value == null ? null : detail.value?.updatedAt ?? null
}
function localDraftContent(): PiLocalDraftContent {
  return {
    form: draft, step: draftStep.value, editingId: editingId.value, fromLocks: fromLocks.value,
    selectedLocks: selectedLocks.value, baseline: draftBaseline, baseUpdatedAt,
    customerLoadFailed: customerLoadFailed.value || customerBusy.value,
    conversionKey, conversionFingerprint,
    options: {
      customers: options.value.customers.filter(row => row.id === draft.customerId),
      agents: options.value.agents.filter(row => row.id === draft.agentId),
      skus: options.value.skus.filter(row => draft.items.some(item => item.skuId === row.id)),
      orders: options.value.orders.filter(row => row.id === draft.sourceOrderId),
    },
  }
}
function persistLocalDraft(force = false): boolean {
  if (!createVisible.value || initializingDraft || restoringDraft.value || !localDraftId) return false
  if (!force && !activeLocalDraft && draftFingerprint() === draftBaseline) return true
  try {
    const content = localDraftContent(), serialized = JSON.stringify(content), store = localStore(localDraftOwner)
    if (serialized === lastLocalContent && !localSaveError.value) {
      if (store.read(localDraftId)?.serialized !== activeLocalDraft?.serialized) throw new Error('该草稿已在其他页面修改，请保留当前页面并重新打开最新草稿')
      return true
    }
    activeLocalDraft = store.save(localDraftId, content, activeLocalDraft?.serialized ?? null)
    lastLocalContent = serialized; localSavedAt.value = activeLocalDraft.savedAt; localSaveError.value = ''
    refreshLocalDrafts(); return true
  } catch (error) {
    localSaveError.value = error instanceof Error && error.message.includes('草稿') ? error.message : '本机草稿保存失败，请检查浏览器存储空间或设置，当前内容仍保留在页面中'
    return false
  }
}
function saveLocalDraft() { if (persistLocalDraft(true)) ElMessage.success('草稿已保存到本机，可稍后继续填写') }
function saveLocalDraftCopy() {
  localDraftId = createUuid(); activeLocalDraft = null; lastLocalContent = ''
  saveLocalDraft()
}
function finishClosingDraft(done?: () => void) {
  customerLoadVersion++; customerBusy.value = false; lockVersion++; lockBusy.value = false; profileSelectionVersion++
  optionLoadVersion++; optionsBusy.value = false
  leaveDraftVisible.value = false
  if (done) done(); else createVisible.value = false
}
function saveDraftAndLeave() { if (persistLocalDraft(true)) finishClosingDraft() }
function discardDraftAndLeave() {
  try { if (activeLocalDraft) localStore(localDraftOwner).remove(activeLocalDraft) }
  catch (error) { localSaveError.value = message(error, '草稿删除失败，请重试'); return }
  finishClosingDraft(); activeLocalDraft = null; refreshLocalDrafts()
}
async function removeLocalDraft(record: PiLocalDraft) {
  try {
    await ElMessageBox.confirm('删除后无法恢复这份本机草稿。', '删除本机草稿？', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '保留' })
    localStore().remove(record); refreshLocalDrafts()
  } catch (error) { if (error !== 'cancel' && error !== 'close') { refreshLocalDrafts(); localDraftError.value = message(error, '删除草稿失败') } }
}
async function restoreLocalDraft(record: PiLocalDraft) {
  if (!canWrite.value || restoringDraft.value || createVisible.value) return
  restoringDraft.value = true; localDraftError.value = ''
  try {
    const saved = localStore().read(record.id)
    if (!saved) throw new Error('这份本机草稿已删除，请刷新列表')
    const content = saved.content
    if (content.fromLocks && !canManageReservation.value) throw new Error('当前账号没有来源锁定操作权限')
    if (content.editingId != null) {
      const current = await piApi.get(content.editingId)
      if (current.status !== 'DRAFT' || !content.baseUpdatedAt || current.updatedAt !== content.baseUpdatedAt) throw new Error('原 PI 已变更或不再是草稿，请打开最新单据核对；本机草稿已保留')
      detail.value = current
    }
    customerLoadVersion++; lockVersion++; customerBusy.value = false; lockBusy.value = false
    editingId.value = content.editingId; fromLocks.value = content.fromLocks
    Object.assign(draft, { customerId: undefined, agentId: undefined, sourceOrderId: undefined }, content.form)
    selectedLocks.value = content.selectedLocks; options.value = content.options
    customerLoadFailed.value = content.customerLoadFailed
    conversionKey = content.conversionKey; conversionFingerprint = content.conversionFingerprint
    draftBaseline = content.baseline; baseUpdatedAt = content.baseUpdatedAt; draftStep.value = content.step
    draftValidation.value = null; createError.value = ''; optionsError.value = ''; optionKeyword.value = ''
    feesExpanded.value = [draft.discountAmount, draft.taxAmount, draft.freightAmount, draft.otherFeeAmount].some(amount => cents(amount) !== 0n)
    localDraftId = saved.id; localDraftOwner = props.context?.username || ''; activeLocalDraft = saved
    lastLocalContent = ''; localSavedAt.value = saved.savedAt; localSaveError.value = ''
    createVisible.value = true
    // 等待依赖字段的 watcher 执行完毕，恢复期间不得清除来源订单、锁定及商品。
    await nextTick()
    await Promise.all([loadOptions(), loadProfiles()])
    if (fromLocks.value || revisionFulfillment.value) void loadLocks()
  } catch (error) { localDraftError.value = message(error, '恢复本机草稿失败，原内容已保留') }
  finally { restoringDraft.value = false }
}
const draftTitle = computed(() => fromLocks.value ? '从 SKU 锁定生成 PI' : editingId.value == null ? '新建 PI' : '编辑 PI 草稿')
const selectedCustomer = computed(() => options.value.customers.find(customer => customer.id === draft.customerId))
const selectedAgent = computed(() => options.value.agents.find(agent => agent.id === draft.agentId))
const draftQuantity = computed(() => draft.items.reduce((sum, item) => sum + (item.skuId && Number.isSafeInteger(item.quantity) ? item.quantity : 0), 0))
const buyerFields: [keyof PiDocument, string, number][] = [
  ['buyerContactName', '买方联系人', 100], ['buyerPhone', '买方电话', 64], ['buyerEmail', '买方 Email', 254],
  ['buyerCountry', '买方国家 / 地区', 100], ['businessOwner', '业务归属', 150],
  ['buyerContact', '买方联系方式', 300], ['buyerAddress', '买方地址', 1000],
]
function isBuyerRequired(key: keyof PiDocument) {
  return key === 'buyerAddress' || key === 'buyerContact' || key === 'buyerCountry' && draft.tradeType === 'EXPORT'
}
// 保存与发布共用单据必填校验，历史草稿发布时也能定位到可编辑字段。
function documentIssue(document: PiDocument | null | undefined, tradeType: string, step: number) {
  const issue = (field: string, message: string) => ({ step, field, message })
  if (step === 0) {
    if (!document?.buyerAddress?.trim()) return issue('buyerAddress', '请填写买方地址')
    if (!document.buyerContact?.trim()) return issue('buyerContact', '请填写买方联系方式')
    if (tradeType === 'EXPORT' && !document.buyerCountry?.trim()) return issue('buyerCountry', '外销 PI 请填写买方国家 / 地区')
  }
  if (step === 1) {
    if (!document?.sellerName?.trim() || !document.templateCode?.trim() || !document.templateVersion?.trim()) return issue('template', '请选择已发布的完整模板，卖方与银行资料将自动带入')
    if (tradeType === 'EXPORT' && !document.sellerCountry?.trim()) return issue('template', '外销 PI 请选择包含卖方国家 / 地区的完整模板')
    if (!document.bankName?.trim() || !document.bankAccount?.trim() || !document.beneficiary?.trim()) return issue('bank', '请确认本单收款银行、账号和收款人')
    if (!document.paymentTerms?.trim()) return issue('paymentTerms', '请填写本单实际约定的付款条款')
    if (!document.deliveryTerms?.trim()) return issue('deliveryTerms', '请填写本单实际约定的交付条款')
    if (!document.transportMode?.trim()) return issue('transportMode', '请填写运输方式')
    if (!document.issueDate) return issue('issueDate', '请选择开单日期')
    if (!document.validUntil) return issue('validUntil', '请选择有效期')
    if (document.validUntil < document.issueDate) return issue('validUntil', '有效期不能早于开单日期')
    if (tradeType === 'EXPORT' && !document.tradeTerms?.trim()) return issue('tradeTerms', '外销 PI 请填写贸易术语')
    if (!document.shippingAddress?.trim()) return issue('shippingAddress', '请填写收货地址')
  }
  return null
}
function draftFingerprint() { return JSON.stringify({ draft, sources: selectedLocks.value.map(lock => ({ id: lock.id, quantity: lock.quantity })) }) }
function resetDraftWizard() {
  draftStep.value = 0; draftValidation.value = null
  feesExpanded.value = [draft.discountAmount, draft.taxAmount, draft.freightAmount, draft.otherFeeAmount].some(amount => cents(amount) !== 0n)
  draftBaseline = draftFingerprint()
}
function changeTradeType() {
  // 模板与贸易类型绑定；保留客户、商品和条款，只清除不能继续使用的受控资料。
  const hadTemplate = Boolean(draft.document.templateCode)
  for (const key of fixedDocumentFields) Object.assign(draft.document, { [key]: '' })
  if (draft.document.business) { draft.document.business.collectionEntity = draft.tradeType === 'EXPORT' ? 'HONG_KONG' : 'GUANGTA'; draft.document.business.fulfillmentProfileId = null }
  if (hadTemplate) ElMessage.info('贸易类型已切换，请在第二步重新选用匹配模板')
}
watch(templateVisible, (visible, previous) => { if (previous && !visible && createVisible.value) templateRefreshKey.value++ })
function fieldError(field: string) { return draftValidation.value?.field === field ? draftValidation.value.message : undefined }
watch(draft, () => {
  if (!draftValidation.value) return
  const current = stepIssue(draftValidation.value.step)
  if (!current || current.field !== draftValidation.value.field) draftValidation.value = null
}, { deep: true })
function stepIssue(step: number): { step: number; field: string; message: string } | null {
  const issue = (field: string, message: string) => ({ step, field, message })
  if (step === 0) {
    if (optionsError.value) return issue('options', '基础资料尚未加载成功，请重试')
    if (requiresProfile.value && !draft.document.business?.fulfillmentProfileId) return issue('fulfillmentProfile', '请选择已发布的履约配置；新单不能沿用历史规则')
    if (!draft.document.business?.warehouseId) return issue('warehouse', '请先选择出库仓库，不同仓库分别建立 PI')
    if (draft.document.business.fulfillmentProfileId && !draftProfile.value) return issue('fulfillmentProfile', '请先成功加载本单履约配置')
    if (draftProfile.value?.rules.paymentMode === 'CREDIT' && !draft.document.business.paymentDueDate) return issue('paymentDueDate', '后付款订单须填写付款到期日')
    if (draftProfile.value?.rules.warehouseIds.length && !draftProfile.value.rules.warehouseIds.includes(draft.document.business.warehouseId)) return issue('warehouse', '所选仓库不适用此履约配置')
    if (draft.document.business.domesticShipment == null) return issue('warehouse', '请明确是否从国内出货')
    if (!draft.customerId) return issue('customer', '请先选择客户')
    if (!fromLocks.value && !hasBoundOrders.value) {
      if (!draft.document.business.orderSource) return issue('orderSource', '请选择关联已有订单，或明确选择无原订单的新业务')
      if (draft.document.business.orderSource === 'EXISTING_ORDER' && !draft.sourceOrderId) return issue('sourceOrder', '请选择来源销售订单，系统将复用原订单及库存占用')
      if (draft.sourceOrderId && !options.value.orders.some(order => order.id === draft.sourceOrderId && order.skuId && (order.skuQuantity ?? 0) > 0)) return issue('sourceOrder', '该订单缺少精确 SKU 明细，请先核对原订单')
    }
    if (customerBusy.value || customerLoadFailed.value) return issue('customer', '客户资料尚未成功带入，请重试加载客户资料')
    if (!/^[A-Z]{3}$/.test(draft.currencyCode.trim().toUpperCase())) return issue('currency', '请输入 3 位币种代码，如 CNY、USD、EUR')
    if (fromLocks.value && !selectedLocks.value.length) return issue('locks', '请至少选择一条来源 SKU 锁定')
    if (selectedLocks.value.some(lock => !Number.isInteger(lock.quantity) || lock.quantity < 1 || lock.quantity > lock.remainingQty)) return issue('locks', '来源锁定数量须为正整数且不能超过剩余可转数量')
    if (!draft.items.length || draft.items.length > 100) return issue('items', 'PI 需包含 1–100 行商品')
    const seen = new Set<number>()
    for (const [index, item] of draft.items.entries()) {
      const row = `第 ${index + 1} 行`
      if (!item.skuId) return issue(`sku-${item.key}`, `${row}请选择 SKU`)
      if (seen.has(item.skuId)) return issue(`sku-${item.key}`, `${row} SKU 重复，请合并数量`)
      seen.add(item.skuId)
      if (!Number.isInteger(item.quantity) || item.quantity < 1 || item.quantity > 2147483647) return issue(`quantity-${item.key}`, `${row}请输入有效的商品数量`)
      if (!item.unit.trim() || item.unit.length > 32) return issue(`unit-${item.key}`, `${row}请填写 1–32 字单位`)
      const price = cents(item.unitPrice), discount = cents(item.discountAmount), tax = cents(item.taxAmount)
      if (price == null) return issue(`price-${item.key}`, `${row}请输入有效单价，最多两位小数`)
      if (discount == null || tax == null || discount > price * BigInt(item.quantity)) return issue(`adjustment-${item.key}`, `${row}请检查折扣与税费，折扣不能超过商品金额`)
    }
    const missingDocument = documentIssue(draft.document, draft.tradeType, step)
    if (missingDocument) return missingDocument
    if (draft.document.buyerEmail && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(draft.document.buyerEmail)) return issue('buyerEmail', '请检查买方 Email 格式')
  }
  if (step === 1) {
    const missingDocument = documentIssue(draft.document, draft.tradeType, step)
    if (missingDocument) return missingDocument
    const expected = preview.value
    if (!expected || expected.total < 0n || expected.discount > expected.subtotal) return issue('fees', '请检查折扣与费用；最多两位小数，总折扣不能超过商品金额')
    const deposit = cents(draft.depositRequiredAmount), shipment = cents(draft.shipmentRequiredAmount)
    if (deposit == null) return issue('deposit', '请明确填写库存保留门槛；无需门槛时填写 0.00')
    if (shipment == null) return issue('shipment', '请明确填写发货放行门槛；无需门槛时填写 0.00')
    if (deposit > expected.total) return issue('deposit', '库存保留门槛不能超过总金额')
    if (shipment > expected.total || shipment < deposit) return issue('shipment', '发货放行门槛须不低于库存门槛，且不能超过总金额')
  }
  return null
}
async function showDraftIssue(issue: NonNullable<typeof draftValidation.value>) {
  draftValidation.value = issue; draftStep.value = issue.step; createError.value = ''
  if (issue.field === 'fees') feesExpanded.value = true
  await focusDraftIssue()
}
async function focusDraftIssue() {
  await nextTick()
  const issue = draftValidation.value
  if (!createVisible.value || !issue) return
  const target = draftScroll.value?.querySelector<HTMLElement>(`[data-draft-field="${issue.field}"]`)
  // 商品折扣等字段也可能处于折叠区，先展开所有祖先再滚动和聚焦。
  for (let ancestor = target; ancestor && ancestor !== draftScroll.value; ancestor = ancestor.parentElement ?? undefined) {
    if (ancestor instanceof HTMLDetailsElement) ancestor.open = true
  }
  target?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  target?.querySelector<HTMLElement>('input:not([disabled]),textarea:not([disabled]),button:not([disabled]),summary')?.focus({ preventScroll: true })
}
async function goToDraftStep(target: number) {
  if (actionBusy.value || target === draftStep.value) return
  if (target > draftStep.value) {
    if (optionsBusy.value || customerBusy.value) return
    for (let step = 0; step < target; step++) { const issue = stepIssue(step); if (issue) { await showDraftIssue(issue); return } }
  }
  draftValidation.value = null; createError.value = ''; draftStep.value = target
  await nextTick(); draftScroll.value?.scrollTo({ top: 0 })
}
function closeDraft(done?: () => void) {
  if (actionBusy.value || restoringDraft.value || initializingDraft) return
  if (draftFingerprint() !== draftBaseline || activeLocalDraft) { leaveDraftVisible.value = true; return }
  finishClosingDraft(done)
}
const filteredOrders = computed(() => [...new Map(options.value.orders.filter(order => order.customerId === draft.customerId).map(order => [order.id, order])).values()])
function chooseSourceOrder(id?: number) {
  if (!id) return
  const sources = options.value.orders.filter(order => order.id === id)
  const source = sources[0]
  if (!source) return
  draft.agentId = source.agentId ?? undefined
  draft.plannedShipDate = source.plannedShipDate || ''
  if (draft.document.business) draft.document.business.orderSource = 'EXISTING_ORDER'
  if (source.agentId && !options.value.agents.some(agent => agent.id === source.agentId))
    options.value.agents.push({ id: source.agentId, code: '', name: source.agentName || '原订单代理商' })
  draft.items = sources.filter(order => order.skuId && (order.skuQuantity ?? 0) > 0).map(order => {
    if (!options.value.skus.some(sku => sku.id === order.skuId)) options.value.skus.push({ id: order.skuId!, modelId: order.modelId, skuCode: order.skuCode || '', productName: order.productName || '', specification: order.specification, enabled: order.enabled })
    return { ...(draft.items.find(item => item.skuId === order.skuId) || newItem()), skuId: order.skuId!, quantity: order.skuQuantity! }
  })
}
function changeOrderSource() {
  if (!hasBoundOrders.value && draft.document.business?.orderSource === 'NEW_BUSINESS') draft.sourceOrderId = undefined
  if (!draft.items.length) draft.items = [newItem()]
}
const customerBusy = ref(false)
const customerLoadFailed = ref(false)
let customerLoadVersion = 0
async function chooseCustomer(id: number) {
  const version = ++customerLoadVersion; customerBusy.value = true; customerLoadFailed.value = false; createError.value = ''
  Object.assign(draft.document, { buyerAddress: '', buyerCountry: '', shippingAddress: '', buyerContact: '', buyerContactName: '', buyerPhone: '', buyerEmail: '', businessOwner: '' })
  try {
    const customer = await customerProfileApi.get(id)
    if (version !== customerLoadVersion || draft.customerId !== id || !createVisible.value) return
    Object.assign(draft.document, { buyerAddress: customer.address || '', buyerCountry: customer.country || '', shippingAddress: customer.address || '',
      buyerContact: [customer.contactName, customer.contactPhone].filter(Boolean).join(' / '), buyerContactName: customer.contactName || '', buyerPhone: customer.contactPhone || '', buyerEmail: customer.email || '', businessOwner: customer.businessOwner || '' })
    draft.currencyCode = customer.preferredCurrency || 'CNY'
  } catch (error) { if (version === customerLoadVersion) { customerLoadFailed.value = true; createError.value = message(error, '客户商务资料带入失败，请重新选择客户') } }
  finally { if (version === customerLoadVersion) customerBusy.value = false }
}
watch(() => draft.customerId, () => { if (!restoringDraft.value && !initializingDraft && editingId.value == null && !filteredOrders.value.some(order => order.id === draft.sourceOrderId)) draft.sourceOrderId = undefined })
watch([() => draft.customerId, () => draft.agentId], () => {
  if (restoringDraft.value || initializingDraft) return
  lockVersion++; lockRows.value = []; lockTotal.value = 0; lockPage.value = 1; selectedLocks.value = []
  if (fromLocks.value) { draft.items = []; void loadLocks() }
})
async function loadLocks() {
  if ((!fromLocks.value && !revisionFulfillment.value) || !draft.customerId) return
  const version = ++lockVersion; lockBusy.value = true; lockError.value = ''
  try {
    const found = await piApi.lockOptions({ customerId: draft.customerId, agentId: draft.agentId, keyword: lockKeyword.value.trim() || undefined, page: lockPage.value })
    if (version === lockVersion) { lockRows.value = found.records; lockTotal.value = found.total }
  } catch (error) { if (version === lockVersion) lockError.value = message(error, '来源锁定加载失败') }
  finally { if (version === lockVersion) lockBusy.value = false }
}
function syncLockItems() {
  if (revisionFulfillment.value) return
  const grouped = new Map<number, number>()
  for (const lock of selectedLocks.value) grouped.set(lock.skuId, (grouped.get(lock.skuId) || 0) + lock.quantity)
  draft.items = [...grouped].map(([skuId, quantity]) => ({ ...(draft.items.find(item => item.skuId === skuId) || newItem()), skuId, quantity }))
}
function addLock(lock: PiLockOption) {
  if (selectedLocks.value.some(item => item.id === lock.id)) return
  selectedLocks.value.push({ ...lock, quantity: lock.remainingQty })
  if (!options.value.skus.some(item => item.id === lock.skuId)) options.value.skus.push({ id: lock.skuId, skuCode: lock.skuCode, productName: lock.productName, modelId: lock.modelId })
  if (revisionFulfillment.value && !draft.items.some(item => item.skuId === lock.skuId)) draft.items.push({ ...newItem(), skuId: lock.skuId, quantity: lock.remainingQty })
  syncLockItems()
}
function removeLock(id: number) { selectedLocks.value = selectedLocks.value.filter(item => item.id !== id); syncLockItems() }
function removeItem(index: number) {
  const skuId = draft.items[index]?.skuId
  selectedLocks.value = selectedLocks.value.filter(lock => lock.skuId !== skuId)
  draft.items.splice(index, 1)
}

function cents(value: string): bigint | null {
  if (!/^\d{1,16}(\.\d{1,2})?$/.test(value.trim())) return null
  const [whole, fraction = ''] = value.trim().split('.')
  return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'))
}
function fromCents(value: bigint): string { const sign = value < 0n ? '-' : ''; const n = value < 0n ? -value : value; return `${sign}${n / 100n}.${String(n % 100n).padStart(2, '0')}` }
function money(value: Money | null | undefined): string {
  if (value == null) return '—'
  const text = String(value), parsed = cents(text)
  return parsed == null ? text : fromCents(parsed)
}
function shipmentProgress(row: PiView): string {
  if (!row.fulfillment?.length) return '待建立履约订单'
  const shipped = row.fulfillment.reduce((sum, item) => sum + item.shippedQty, 0)
  const remaining = row.fulfillment.reduce((sum, item) => sum + item.remainingQty, 0)
  return `已发 ${shipped} / 未发 ${remaining}`
}
function lineTotal(item: DraftItem): bigint | null {
  const price = cents(item.unitPrice), discount = cents(item.discountAmount), tax = cents(item.taxAmount)
  if (price == null || discount == null || tax == null || !Number.isSafeInteger(item.quantity) || item.quantity < 1) return null
  return price * BigInt(item.quantity) - discount + tax
}
const preview = computed(() => {
  let subtotal = 0n, discount = cents(draft.discountAmount), tax = cents(draft.taxAmount)
  const freight = cents(draft.freightAmount), otherFees = cents(draft.otherFeeAmount)
  if (discount == null || tax == null || freight == null || otherFees == null) return null
  for (const item of draft.items) {
    const price = cents(item.unitPrice), lineDiscount = cents(item.discountAmount), lineTax = cents(item.taxAmount)
    if (price == null || lineDiscount == null || lineTax == null || !Number.isSafeInteger(item.quantity) || item.quantity < 1) return null
    subtotal += price * BigInt(item.quantity); discount += lineDiscount; tax += lineTax
  }
  return { subtotal, discount, tax, freight, otherFees, total: subtotal - discount + tax + freight + otherFees }
})
function message(error: unknown, fallback: string) { return api.isAxiosError(error) ? error.response?.data?.message ?? fallback : error instanceof Error ? error.message : fallback }
let listVersion = 0, detailVersion = 0
async function load() {
  if (!canRead.value) return
  const version = ++listVersion; busy.value = true; loadError.value = ''
  try { const result = await piApi.list({ keyword: keyword.value.trim() || undefined, status: status.value || undefined, page: page.value, pageSize: pageSize.value }); if (version === listVersion) { rows.value = result.records; total.value = result.total; reportRefresh.value++ } }
  catch (error) { if (version === listVersion) { rows.value = []; total.value = 0; loadError.value = message(error, 'PI 列表加载失败') } }
  finally { if (version === listVersion) busy.value = false }
}
function search() { page.value = 1; void load() }
async function exportList() {
  if (exportBusy.value) return
  exportBusy.value = true; loadError.value = ''
  try {
    const blob = await piApi.exportList({ keyword: keyword.value.trim() || undefined, status: status.value || undefined })
    const url = URL.createObjectURL(blob), link = document.createElement('a'); link.href = url; link.download = 'PI列表.xlsx'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (e) {
    if (api.isAxiosError(e) && e.response?.data instanceof Blob) { try { loadError.value = JSON.parse(await e.response.data.text()).message || 'PI列表导出失败' } catch { loadError.value = 'PI列表导出失败' } }
    else loadError.value = message(e, 'PI列表导出失败')
  } finally { exportBusy.value = false }
}
async function openDetail(row: Pick<PiView, 'id'>) {
  const version = ++detailVersion; detail.value = null; workflow.value = null; detailVisible.value = true; detailBusy.value = true; detailTab.value = 'items'; operationError.value = ''; workflowError.value = ''
  try { const result = await piApi.get(row.id); if (version === detailVersion) { detail.value = result; if (internal.value) void loadWorkflow(row.id, version) } }
  catch (error) { if (version === detailVersion) operationError.value = message(error, 'PI 详情加载失败') }
  finally { if (version === detailVersion) detailBusy.value = false }
}
async function loadWorkflow(piId: number, version = detailVersion) {
  workflowBusy.value = true; workflowError.value = ''
  try { const result = await piApi.workflow(piId); if (version === detailVersion && detailVisible.value && detail.value?.id === piId) workflow.value = result }
  catch (error) { if (version === detailVersion) workflowError.value = message(error, 'PI流程加载失败') }
  finally { if (version === detailVersion) workflowBusy.value = false }
}
async function completeWorkflow(stepCode: string, body: PiWorkflowComplete) {
  if (!detail.value || (!canWrite.value && !(stepCode === 'CREDIT_APPROVED' && canRelease.value) && !(stepCode === 'INVENTORY_RESERVED' && (canRelease.value || canPay.value))) || workflowActionBusy.value) return
  workflowActionBusy.value = true; workflowError.value = ''
  try { const piId = detail.value.id; workflow.value = await piApi.completeWorkflow(piId, stepCode, body); if (stepCode === 'INVENTORY_RESERVED') detail.value = await piApi.get(piId); ElMessage.success('已保存，流程已进入下一步') }
  catch (error) { workflowError.value = message(error, 'PI流程步骤保存失败') }
  finally { workflowActionBusy.value = false }
}
async function recordProductionUpdate(body: PiWorkflowComplete) {
  if (!detail.value || !canWrite.value || workflowActionBusy.value) return
  workflowActionBusy.value = true; workflowError.value = ''
  try {
    workflow.value = await piApi.recordProductionUpdate(detail.value.id, body)
    ElMessage.success(body.productionCompleted ? '生产跟进已完成' : '本次生产跟进已保存')
  } catch (error) { workflowError.value = message(error, '生产跟进保存失败') }
  finally { workflowActionBusy.value = false }
}
function handleWorkflowAction(action: 'CONFIRM_PI' | 'REGISTER_PAYMENT' | 'RELEASE_PI' | 'REGISTER_LOGISTICS') {
  if (action === 'CONFIRM_PI') void confirmPi()
  else if (action === 'REGISTER_PAYMENT') openPayment()
  else if (action === 'RELEASE_PI') openRelease()
  else openLogistics()
}
let optionLoadVersion = 0
async function loadOptions() {
  const version = ++optionLoadVersion
  optionsBusy.value = true; optionsError.value = ''
  try {
    const found = await piApi.options(optionKeyword.value.trim())
    if (version !== optionLoadVersion) return
    const keepSelected = <T extends { id: number }>(fresh: T[], previous: T[], selected: (number | undefined)[]) => [...fresh, ...previous.filter(item => selected.includes(item.id) && !fresh.some(match => match.id === item.id))]
    options.value = {
      customers: keepSelected(found.customers, options.value.customers, [draft.customerId]),
      agents: keepSelected(found.agents, options.value.agents, [draft.agentId]),
      skus: keepSelected(found.skus, options.value.skus, draft.items.map(item => item.skuId)),
      orders: keepSelected(found.orders, options.value.orders, [draft.sourceOrderId]),
      warehouses: found.warehouses || [],
    }
  }
  catch (error) { if (version === optionLoadVersion) optionsError.value = message(error, 'PI 基础资料加载失败') }
  finally { if (version === optionLoadVersion) optionsBusy.value = false }
}
async function openCreate(mode: 'new' | 'locks' = 'new') {
  if (!canWrite.value || restoringDraft.value || createVisible.value) return
  initializingDraft = true
  customerLoadVersion++; customerBusy.value = false; customerLoadFailed.value = false
  editingId.value = null
  fromLocks.value = mode === 'locks'; selectedLocks.value = []; conversionKey = ''; conversionFingerprint = ''
  Object.assign(draft, { customerId: undefined, agentId: undefined, sourceOrderId: undefined, tradeType: 'DOMESTIC', currencyCode: 'CNY', plannedShipDate: '', discountAmount: '0.00', taxAmount: '0.00', freightAmount: '0.00', otherFeeAmount: '0.00', depositRequiredAmount: '', shipmentRequiredAmount: '', remark: '', document: emptyDocument(), items: [newItem()] })
  if (fromLocks.value) draft.items = []
  resetDraftWizard(); beginLocalDraft(); createError.value = ''; optionKeyword.value = ''; createVisible.value = true
  await nextTick(); initializingDraft = false; await loadOptions()
}
async function openConversion() { await openCreate('locks') }
async function openEdit() {
  const current = detail.value
  if (!canWrite.value || !current || current.status !== 'DRAFT' || restoringDraft.value || createVisible.value) return
  initializingDraft = true
  customerLoadVersion++; customerBusy.value = false; customerLoadFailed.value = false
  editingId.value = current.id
  fromLocks.value = false; selectedLocks.value = []
  const lineDiscount = current.items.reduce((sum, item) => sum + (cents(String(item.discountAmount)) ?? 0n), 0n)
  const lineTax = current.items.reduce((sum, item) => sum + (cents(String(item.taxAmount)) ?? 0n), 0n)
  Object.assign(draft, {
    customerId: current.customerId, agentId: current.agentId ?? undefined, sourceOrderId: current.sourceOrderId ?? undefined,
    tradeType: current.tradeType, currencyCode: current.currencyCode, plannedShipDate: current.plannedShipDate || '',
    discountAmount: fromCents((cents(String(current.discountAmount)) ?? 0n) - lineDiscount),
    taxAmount: fromCents((cents(String(current.taxAmount)) ?? 0n) - lineTax), freightAmount: money(current.freightAmount), otherFeeAmount: money(current.otherFeeAmount ?? '0.00'),
    depositRequiredAmount: current.depositRequiredAmount == null ? '' : money(current.depositRequiredAmount),
    shipmentRequiredAmount: current.shipmentRequiredAmount == null ? '' : money(current.shipmentRequiredAmount),
    remark: current.remark || '', document: { ...emptyDocument(), ...current.document, business: { ...(current.document?.business ?? { warehouseId: null, domesticShipment: null, collectionEntity: current.tradeType === 'EXPORT' ? 'HONG_KONG' : 'GUANGTA' }) } },
    items: current.items.map(item => ({ key: createUuid(), skuId: item.skuId, quantity: item.quantity, unitPrice: money(item.unitPrice), discountAmount: money(item.discountAmount), taxAmount: money(item.taxAmount), unit: item.unit || '台', imageAttachmentId: item.imageAttachmentId })),
  })
  options.value = {
    customers: [{ id: current.customerId, code: current.customerCode || '', name: current.customerName }],
    agents: current.agentId ? [{ id: current.agentId, code: '', name: current.agentName || '所属代理商' }] : [],
    orders: current.sourceOrderId ? [{ id: current.sourceOrderId, orderNo: '原来源订单', customerId: current.customerId, agentId: current.agentId, status: 'CONFIRMED' }] : [],
    skus: current.items.map(item => ({ id: item.skuId, productModelId: item.productModelId, skuCode: item.skuCode, productName: item.productName, specification: item.specification })),
  }
  selectedLocks.value = (current.revisionSources || []).filter(source => !source.orderId).map(source => ({ id: source.lockId, skuId: source.skuId, quantity: source.quantity, remainingQty: source.quantity, lockNo: source.lockNo, skuCode: source.skuCode, productName: source.productName, modelId: current.items.find(item => item.skuId === source.skuId)?.productModelId || 0, expireTime: source.expiresAt }))
  resetDraftWizard(); beginLocalDraft(); createError.value = ''; optionKeyword.value = ''; createVisible.value = true
  await nextTick(); initializingDraft = false; await loadOptions()
}
function payload(): PiCreate {
  if (!draft.customerId) throw new Error('请选择客户')
  if (draft.document.issueDate && draft.document.validUntil && draft.document.validUntil < draft.document.issueDate) throw new Error('有效期不能早于开单日期')
  if (!draft.document.sellerName.trim() || !draft.document.paymentTerms.trim() || !draft.document.deliveryTerms.trim() || !draft.document.templateCode.trim() || !draft.document.templateVersion.trim()) throw new Error('请填写卖方名称、付款条款、交付条款、模板名称及版本')
  if (!/^[A-Z]{3}$/.test(draft.currencyCode.trim().toUpperCase())) throw new Error('币种需填写 3 位代码，如 CNY、USD、EUR')
  const expected = preview.value
  if (!expected || expected.total < 0n || expected.discount > expected.subtotal) throw new Error('请检查商品金额、折扣、税费和运费；金额最多保留两位小数')
  const deposit = cents(draft.depositRequiredAmount), shipment = cents(draft.shipmentRequiredAmount)
  if (deposit == null || shipment == null) throw new Error('请明确填写两个收款门槛；无需门槛时填写 0.00')
  if (deposit > expected.total || shipment > expected.total || shipment < deposit) throw new Error('发货门槛须不低于库存门槛，两个门槛均不能超过总金额')
  if (!draft.items.length || draft.items.length > 100) throw new Error('PI 需包含 1–100 行商品')
  const seen = new Set<number>()
  const items = draft.items.map((item, index) => {
    if (!item.skuId) throw new Error(`第 ${index + 1} 行请选择 SKU`)
    if (seen.has(item.skuId)) throw new Error(`第 ${index + 1} 行 SKU 重复，请合并数量`)
    seen.add(item.skuId)
    const price = cents(item.unitPrice), discount = cents(item.discountAmount), tax = cents(item.taxAmount)
    if (price == null || discount == null || tax == null || !Number.isInteger(item.quantity) || item.quantity < 1 || item.quantity > 2147483647) throw new Error(`第 ${index + 1} 行数量或金额无效`)
    if (discount > price * BigInt(item.quantity)) throw new Error(`第 ${index + 1} 行折扣超过商品金额`)
    if (!item.unit.trim() || item.unit.length > 32) throw new Error(`第 ${index + 1} 行请填写1至32字单位`)
    return { skuId: item.skuId, quantity: item.quantity, unitPrice: fromCents(price), discountAmount: fromCents(discount), taxAmount: fromCents(tax), unit: item.unit.trim(), imageAttachmentId: item.imageAttachmentId }
  })
  return { customerId: draft.customerId, agentId: draft.agentId ?? null, sourceOrderId: draft.sourceOrderId ?? null, tradeType: draft.tradeType, currencyCode: draft.currencyCode.trim().toUpperCase(), plannedShipDate: draft.plannedShipDate || null, discountAmount: fromCents(cents(draft.discountAmount)!), taxAmount: fromCents(cents(draft.taxAmount)!), freightAmount: fromCents(cents(draft.freightAmount)!), otherFeeAmount: fromCents(cents(draft.otherFeeAmount)!), depositRequiredAmount: fromCents(deposit), shipmentRequiredAmount: fromCents(shipment), remark: draft.remark, document: { ...draft.document }, items, revisionSources: revisionFulfillment.value ? selectedLocks.value.map(lock => ({ lockId: lock.id, quantity: lock.quantity })) : [] }
}
async function saveCreate() {
  if (!canWrite.value || actionBusy.value || optionsBusy.value) return
  for (const step of [0, 1]) { const issue = stepIssue(step); if (issue) { await showDraftIssue(issue); return } }
  if (customerBusy.value || customerLoadFailed.value) { createError.value = '客户商务资料尚未成功带入，请重新选择客户后再保存'; return }
  createError.value = ''
  try {
    const body = payload(); actionBusy.value = true
    if (editingId.value != null && baseUpdatedAt) {
      const current = await piApi.get(editingId.value)
      if (current.status !== 'DRAFT' || current.updatedAt !== baseUpdatedAt) throw new Error('原 PI 已变更，请重新打开最新单据核对；本机草稿已保留')
    }
    let created: PiView
    if (fromLocks.value) {
      if (!selectedLocks.value.length) throw new Error('请选择至少一条来源SKU锁定')
      const sources = selectedLocks.value.map(lock => ({ lockId: lock.id, quantity: lock.quantity })).sort((a,b) => a.lockId - b.lockId)
      const fingerprint = JSON.stringify({ sources, pi: body })
      if (fingerprint !== conversionFingerprint) { conversionFingerprint = fingerprint; conversionKey = createUuid() }
      persistLocalDraft(true)
      created = await piApi.convert({ requestId: conversionKey, sources, pi: body })
    } else created = editingId.value == null ? await piApi.create(body) : await piApi.update(editingId.value, body)
    createVisible.value = false
    try { if (activeLocalDraft) localStore(localDraftOwner).remove(activeLocalDraft) }
    catch { ElMessage.warning('PI 已保存，但本机草稿未能清除，请在草稿列表中删除对应记录，避免重复提交') }
    activeLocalDraft = null; refreshLocalDrafts()
    await load(); await openDetail(created); ElMessage.success('PI 草稿已保存')
  }
  catch (error) { createError.value = message(error, 'PI 创建失败，输入内容已保留') }
  finally { actionBusy.value = false }
}
async function cancelPi() {
  if (!detail.value || !canWrite.value || actionBusy.value) return
  try {
    const prompt = detail.value.previousRevisionId && detail.value.status === 'DRAFT'
      ? '请输入取消本次修订的原因。当前有效版本、收款和库存占用将保留。'
      : '请输入取消原因。已有收款、已放行或已发货的 PI 不可取消；关联订单仍保留该单据的处理限制。'
    const answer = await ElMessageBox.prompt(prompt, '取消 PI', { confirmButtonText: '确认取消', cancelButtonText: '保留单据', inputType: 'textarea', inputValidator: (value: string) => value.trim().length > 0 && value.length <= 500 ? true : '请输入 1–500 字原因' })
    actionBusy.value = true; operationError.value = ''; detail.value = await piApi.cancel(detail.value.id, answer.value.trim()); await load(); ElMessage.success('PI 已取消')
  } catch (error) { if (error !== 'cancel' && error !== 'close') operationError.value = message(error, 'PI 取消失败') }
  finally { actionBusy.value = false }
}
async function confirmPi() {
  if (!detail.value || !canWrite.value || actionBusy.value || createVisible.value) return
  const current = detail.value
  const issue = documentIssue(current.document, current.tradeType, 0) || documentIssue(current.document, current.tradeType, 1)
  if (issue) {
    operationError.value = ''
    await openEdit()
    if (createVisible.value && editingId.value === current.id) await showDraftIssue(issue)
    return
  }
  try {
    await ElMessageBox.confirm('确认后，单据将向所属代理商发布。请核对商品、金额、双方资料和条款。', '确认 PI', { confirmButtonText: '确认发布', cancelButtonText: '返回核对', type: 'warning' })
    actionBusy.value = true; operationError.value = ''; detail.value = await piApi.confirm(detail.value.id); await loadWorkflow(detail.value.id); await load(); ElMessage.success('PI 已确认')
  } catch (error) { if (error !== 'cancel' && error !== 'close') operationError.value = message(error, 'PI 确认失败') }
  finally { actionBusy.value = false }
}
const paymentVisible = ref(false), paymentError = ref('')
const receiptVisible = ref(false), receiptPayment = ref<PiPayment | null>(null)
function openReceipt(payment: PiPayment) { if (!showFinance.value) return; receiptPayment.value = payment; receiptVisible.value = true }
watch(detailVisible, value => { if (!value) { receiptVisible.value = false; logisticsAttachmentVisible.value = false; logisticsAttachment.value = null; batchVisible.value = false; batchVehicles.value = [] } })
watch(showFinance, value => { if (!value) { receiptVisible.value = false; receiptPayment.value = null } })
const paymentMethods: Record<string, string> = { BANK_TRANSFER: '银行转账', CASH: '现金', OTHER: '其他' }
const payment = reactive({ requestId: '', amount: '', currencyCode: '', receivedAt: '', remark: '', paymentMethod: '', paymentMethodNote: '' })
function openPayment() { if (!detail.value || !canPay.value) return; Object.assign(payment, { requestId: createUuid(), amount: '', currencyCode: detail.value.currencyCode, receivedAt: '', remark: '', paymentMethod: '', paymentMethodNote: '' }); paymentError.value = ''; paymentVisible.value = true }
async function savePayment() {
  if (!detail.value || !canPay.value || actionBusy.value) return
  if (!payment.paymentMethod || payment.paymentMethod === 'OTHER' && !payment.paymentMethodNote.trim()) { paymentError.value = '请选择收款方式，其他方式须填写说明'; return }
  const amount = cents(payment.amount)
  if (amount == null || amount < 1n) { paymentError.value = '请输入大于 0 且最多两位小数的到账金额'; return }
  actionBusy.value = true; paymentError.value = ''
  try { detail.value = await piApi.payment(detail.value.id, { ...payment, amount: fromCents(amount), receivedAt: payment.receivedAt || null }); paymentVisible.value = false; detailTab.value = 'payment'; await loadWorkflow(detail.value.id); await load(); ElMessage.success('到账记录已保存，流程已自动推进') }
  catch (error) { paymentError.value = message(error, '收款登记失败，保留当前表单可安全重试') }
  finally { actionBusy.value = false }
}
const reversalVisible = ref(false), reversalError = ref('')
const reversal = reactive({ paymentId: 0, requestId: '', revision: 1, reason: '' })
function openPaymentReversal(row: PiPayment) {
  if (!canReversePayment.value || row.status !== 'CONFIRMED') return
  Object.assign(reversal, { paymentId: row.id, requestId: createUuid(), revision: row.revision, reason: '' }); reversalError.value = ''; reversalVisible.value = true
}
async function savePaymentReversal() {
  if (!detail.value || !canReversePayment.value || actionBusy.value) return
  if (!reversal.reason.trim()) { reversalError.value = '请填写错误登记原因'; return }
  actionBusy.value = true; reversalError.value = ''
  try { detail.value = await piApi.reversePayment(detail.value.id, reversal.paymentId, reversal); reversalVisible.value = false; await load(); ElMessage.success('错误到账登记已撤回，请核对当前版本并重新放行') }
  catch (error) { reversalError.value = message(error, '撤回失败，请核对最新收款记录') }
  finally { actionBusy.value = false }
}
const releaseVisible = ref(false), releaseReason = ref(''), releaseError = ref('')
const reservationBlocked = computed(() => Boolean(detail.value?.fulfillment?.length ? detail.value.fulfillment.some(line => line.remainingQty > 0 && ['EXPIRED_REQUIRES_REACQUIRE', 'RELEASED'].includes(line.reservationStatus)) : detail.value?.sourceOrderId && ['EXPIRED_REQUIRES_REACQUIRE', 'RELEASED'].includes(detail.value.reservationStatus ?? '')))
const canRenewReservation = computed(() => canManageReservation.value && Boolean(detail.value?.sourceOrderId) && ['TIMED_ACTIVE', 'EXPIRED_REQUIRES_REACQUIRE'].includes(detail.value?.reservationStatus ?? ''))
const reservationVisible = ref(false), reservationError = ref('')
const reservation = reactive({ hours: 48, reason: '' })
const reservationOrderId = ref<number>()
function openReservation(orderId?: number) {
  if (!canManageReservation.value || (!orderId && !canRenewReservation.value)) return
  reservationOrderId.value = orderId || detail.value?.sourceOrderId || undefined
  Object.assign(reservation, { hours: 48, reason: '' }); reservationError.value = ''; reservationVisible.value = true
}
async function saveReservation() {
  const current = detail.value
  if (!current || !reservationOrderId.value || !canManageReservation.value || actionBusy.value) return
  if (!Number.isInteger(reservation.hours) || reservation.hours < 1 || reservation.hours > 720 || !reservation.reason.trim() || reservation.reason.length > 500) { reservationError.value = '请填写 1–720 小时及 1–500 字重新占用原因'; return }
  actionBusy.value = true; reservationError.value = ''
  try {
    await piApi.renewReservation(reservationOrderId.value, { hours: reservation.hours, reason: reservation.reason.trim() })
    reservationVisible.value = false
    await openDetail(current); await load(); ElMessage.success('库存已重新核对并占用，请核对最新占用状态')
  } catch (error) { reservationError.value = message(error, '重新占用失败，输入内容已保留') }
  finally { actionBusy.value = false }
}
const gateMet = computed(() => { if (detailProfile.value?.rules.paymentMode === 'CREDIT') return workflow.value?.steps.some(s => s.code === 'CREDIT_APPROVED' && s.status === 'COMPLETED') ?? false; if (!detail.value) return false; const paid = cents(String(detail.value.paidAmount ?? '')), gate = cents(String(detail.value.shipmentRequiredAmount ?? '')); return paid != null && gate != null && paid >= gate })
function openRelease() { releaseReason.value = ''; releaseError.value = ''; releaseVisible.value = true }
async function saveRelease() {
  if (!detail.value || !canRelease.value || actionBusy.value) return
  if (reservationBlocked.value) { releaseError.value = '来源订单占用已失效，请先处理库存占用'; return }
  if (!releaseReason.value.trim()) { releaseError.value = '请填写发货放行说明'; return }
  actionBusy.value = true; releaseError.value = ''
  try { detail.value = await piApi.release(detail.value.id, releaseReason.value.trim()); releaseVisible.value = false; await loadWorkflow(detail.value.id); await load(); ElMessage.success('PI 已放行发货') }
  catch (error) { releaseError.value = message(error, '发货放行失败') }
  finally { actionBusy.value = false }
}
const logisticsVisible = ref(false), logisticsError = ref('')
const customerArrangedLogistics = computed(() => workflow.value?.steps.find(s => s.code === 'FORWARDER_SELECTED')?.details?.forwarderResponsibility === 'CUSTOMER')
const logistics = reactive({ batchNo: '', carrier: '', trackingNo: '', status: 'PLANNED', remark: '', stockOutBatchId: undefined as number | undefined, expectedRevision: undefined as number | undefined })
const logisticsEditingId = ref<number>(), logisticsHistoryVisible = ref(false), logisticsHistoryBusy = ref(false), logisticsHistoryError = ref('')
const logisticsHistory = ref<Array<{ revision: number; action: string; createdAt: string; carrier?: string; trackingNo?: string; status?: string; shippedQtySnapshot?: number; revokeReason?: string }>>([])
const logisticsStates: Record<string,string> = { PLANNED: '计划中', SHIPPED: '已发运', WITHDRAWN: '已撤回' }
let logisticsKey = '', logisticsFingerprint = '', historyVersion = 0
function openLogistics(row?: PiLogistics) {
  logisticsEditingId.value = row?.id; logisticsKey = ''; logisticsFingerprint = ''
  Object.assign(logistics, { batchNo: row?.batchNo || '', carrier: row?.carrier || '', trackingNo: row?.trackingNo || '', status: row?.status === 'SHIPPED' ? 'SHIPPED' : 'PLANNED', remark: row?.remark || '', stockOutBatchId: row?.stockOutBatchId || undefined, expectedRevision: row?.revision })
  logisticsError.value = ''; logisticsVisible.value = true
}
watch(() => logistics.stockOutBatchId, id => { const batch = detail.value?.dispatchBatches?.find(row => row.id === id); if (batch) logistics.batchNo = batch.batchNo })
async function withdrawLogistics(row: PiLogistics) {
  if (!detail.value || !canLogistics.value || actionBusy.value) return
  try {
    const answer = await ElMessageBox.prompt('请核对承运商与实际货物后填写撤回原因。撤回历史和原运单会保留。', '撤回物流', { inputValidator: (value: string) => value.trim().length > 0 && value.length <= 500 ? true : '请填写1–500字原因' })
    actionBusy.value = true
    detail.value = await piApi.withdrawLogistics(detail.value.id,row.id,{ requestId: createUuid(), expectedRevision: row.revision, reason: answer.value.trim() }); await load()
  } catch(error) { if(error !== 'cancel' && error !== 'close') operationError.value = message(error,'物流撤回失败，请刷新后核对') }
  finally { actionBusy.value = false }
}
async function openLogisticsHistory(row: PiLogistics) {
  if (!detail.value || !internal.value) return
  const version = ++historyVersion; logisticsHistoryVisible.value = true; logisticsHistoryBusy.value = true; logisticsHistoryError.value = ''; logisticsHistory.value = []
  try {
    const history = await piApi.logisticsHistory(detail.value.id,row.id)
    if (version === historyVersion && logisticsHistoryVisible.value) logisticsHistory.value = history.map(entry => ({ ...JSON.parse(entry.snapshot), revision: entry.revision, action: entry.action, createdAt: entry.createdAt }))
  } catch(error) { if(version === historyVersion) logisticsHistoryError.value = message(error,'物流历史加载失败') }
  finally { if(version === historyVersion) logisticsHistoryBusy.value = false }
}
watch(detailVisible, value => { if(!value) { ++historyVersion; logisticsHistoryVisible.value = false; logisticsHistory.value = [] } })
async function saveLogistics() {
  if (!detail.value || !canLogistics.value || actionBusy.value) return
  if (!logistics.batchNo.trim()) { logisticsError.value = '请填写物流批次'; return }
  if (logistics.status === 'SHIPPED' && (!logistics.stockOutBatchId || !customerArrangedLogistics.value && (!logistics.carrier.trim() || !logistics.trackingNo.trim()) || customerArrangedLogistics.value && (!logistics.carrier.trim() || !logistics.trackingNo.trim()) && !logistics.remark.trim())) { logisticsError.value = '确认发运须选择真实批次；我方须填承运商及单号，客户安排的未知信息须备注说明'; return }
  actionBusy.value = true; logisticsError.value = ''
  try {
    const fingerprint = JSON.stringify({ piId: detail.value.id, logisticsId: logisticsEditingId.value, ...logistics })
    if(fingerprint !== logisticsFingerprint) { logisticsFingerprint = fingerprint; logisticsKey = createUuid() }
    const body = { ...logistics, stockOutBatchId: logistics.stockOutBatchId ?? null, requestId: logisticsKey }
    detail.value = logisticsEditingId.value ? await piApi.updateLogistics(detail.value.id,logisticsEditingId.value,body) : await piApi.logistics(detail.value.id,body)
    logisticsVisible.value = false; await loadWorkflow(detail.value.id); await load(); ElMessage.success(logistics.status === 'SHIPPED' ? '国际运输已保存，流程已自动推进' : '物流计划已保存')
  }
  catch (error) { logisticsError.value = message(error, '物流登记失败') }
  finally { actionBusy.value = false }
}
async function download(format: 'pdf' | 'xlsx') {
  if (!detail.value || !canExport.value || actionBusy.value) return
  actionBusy.value = true; operationError.value = ''
  try {
    const blob = await piApi.download(detail.value.id, format), url = URL.createObjectURL(blob), link = document.createElement('a')
    link.href = url; link.download = `${detail.value.piNo}-v${detail.value.revision}.${format}`; document.body.appendChild(link); link.click(); link.remove(); window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (error) {
    if (api.isAxiosError(error) && error.response?.data instanceof Blob) { try { operationError.value = JSON.parse(await error.response.data.text()).message ?? '文件下载失败' } catch { operationError.value = '文件下载失败' } }
    else operationError.value = message(error, '文件下载失败')
  } finally { actionBusy.value = false }
}
watch(canRead, value => { if (value) void load(); else { rows.value = []; detail.value = null; detailVisible.value = false } })
watch([draft, selectedLocks, draftStep, customerBusy, customerLoadFailed], () => { persistLocalDraft() }, { deep: true, flush: 'post' })
watch(() => props.context?.username, () => {
  if (createVisible.value && localDraftOwner !== props.context?.username) { persistLocalDraft(); finishClosingDraft() }
  refreshLocalDrafts()
})
function beforePageUnload(event: BeforeUnloadEvent) {
  if (createVisible.value && !persistLocalDraft()) { event.preventDefault(); event.returnValue = '' }
}
function saveOnPageHide() { persistLocalDraft() }
onMounted(() => {
  refreshLocalDrafts()
  window.addEventListener('beforeunload', beforePageUnload)
  window.addEventListener('pagehide', saveOnPageHide)
  window.addEventListener('storage', refreshLocalDrafts)
  if (canRead.value) { void load(); void loadProfiles(); if (internal.value && new URLSearchParams(window.location.search).get('panel') === 'profiles') profilesVisible.value = true }
})
onBeforeUnmount(() => {
  persistLocalDraft(); customerLoadVersion++; lockVersion++; profileSelectionVersion++; optionLoadVersion++
  window.removeEventListener('beforeunload', beforePageUnload)
  window.removeEventListener('pagehide', saveOnPageHide)
  window.removeEventListener('storage', refreshLocalDrafts)
})
</script>

<template>
  <el-dialog v-model="profilesVisible" title="统一履约配置" width="min(1100px,96vw)" append-to-body destroy-on-close><PiFulfillmentProfiles v-if="profilesVisible" :can-manage="canManageTemplates" @changed="loadProfiles" /></el-dialog>
  <el-dialog v-model="templateVisible" title="PI模板与银行资料" width="min(900px, 94vw)" append-to-body destroy-on-close><PiTemplates v-if="templateVisible" :can-manage="canManageTemplates" /></el-dialog>
  <section class="pi-workspace">
    <el-empty v-if="!canRead" description="当前账号没有 PI 查看权限" />
    <template v-else>
      <div class="pi-heading"><div><h2>{{ isAgent ? '我的 PI 单据' : 'PI 与销售履约' }}</h2><p>{{ isAgent ? '查看已发布的单据、商品和物流进度。' : '维护商品与交易条款，跟进收款、放行和物流。' }}</p></div></div>
      <PiBusinessDashboard v-if="internal" :refresh="reportRefresh" @open-pi="id => openDetail({ id })" />
      <form class="pi-filters list-toolbar" @submit.prevent="search"><el-input v-model="keyword" aria-label="搜索PI、客户、SKU、订单、出库单或物流单号" placeholder="PI / 客户 / SKU / 订单 / 出库单 / 物流单号" clearable /><el-select v-model="status" aria-label="PI 状态" clearable placeholder="全部状态"><el-option v-for="s in states" :key="s" :value="s" :label="stateNames[s]" /></el-select><el-button native-type="submit" type="primary" :loading="busy">查询</el-button><el-button @click="keyword = ''; status = ''; search()">重置</el-button><el-button v-if="canExport" :loading="exportBusy" @click="exportList">导出当前查询全部 PI</el-button><el-button v-if="internal" @click="profilesVisible = true">履约配置</el-button><el-button v-if="canManageTemplates" @click="templateVisible = true">模板与银行资料</el-button><el-button v-if="canWrite && canManageReservation" @click="openConversion">从 SKU 锁定生成 PI</el-button><el-button v-if="canWrite" type="primary" @click="openCreate()">新建 PI</el-button></form>
<el-alert v-if="internal && profilesError" :title="profilesError" type="error" :closable="false"><el-button text @click="loadProfiles">重试加载履约配置</el-button></el-alert>
      <el-alert v-if="canWrite && localDraftError" :title="localDraftError" type="error" :closable="false"><el-button text @click="refreshLocalDrafts">重新读取</el-button></el-alert>
      <section v-if="canWrite && localDrafts.length" class="pi-local-drafts" aria-label="本机草稿">
        <div class="pi-local-drafts-heading"><strong>待继续填写的本机草稿（{{ localDrafts.length }}）</strong><span>仅当前浏览器、当前账号可恢复；清理浏览器数据会删除草稿。</span></div>
        <article v-for="saved in localDrafts" :key="saved.id">
          <div><strong>{{ saved.content.options.customers[0]?.name || '未选择客户' }}</strong><p>{{ saved.content.editingId ? '编辑 PI #' + saved.content.editingId : saved.content.fromLocks ? '从 SKU 锁定生成 PI' : '新建 PI' }} · 第 {{ saved.content.step + 1 }} 步 · {{ new Date(saved.savedAt).toLocaleString() }}</p></div>
          <div class="pi-local-draft-actions"><el-button type="primary" plain :disabled="restoringDraft || createVisible" @click="restoreLocalDraft(saved)">继续填写</el-button><el-button text type="danger" :disabled="restoringDraft || createVisible" @click="removeLocalDraft(saved)">删除</el-button></div>
        </article>
      </section>
      <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false" />
      <el-table v-loading="busy" :data="rows" empty-text="没有符合条件的 PI" row-key="id">
      <RecordIdColumn />
        <el-table-column prop="piNo" label="PI 编号" min-width="200"><template #default="scope"><el-button link type="primary" @click="openDetail(scope.row)">{{ scope.row.piNo }}</el-button></template></el-table-column>
        <el-table-column prop="revision" label="版本" width="70" /><el-table-column prop="customerName" label="客户" min-width="150" />
        <el-table-column label="贸易" width="80"><template #default="scope">{{ scope.row.tradeType === 'EXPORT' ? '外销' : '内销' }}</template></el-table-column>
        <el-table-column label="总金额" min-width="145"><template #default="scope">{{ scope.row.currencyCode }} {{ money(scope.row.totalAmount) }}</template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="scope"><el-tag :type="scope.row.status === 'RELEASED' ? 'success' : scope.row.status === 'DRAFT' ? 'info' : 'primary'">{{ stateNames[scope.row.status] ?? scope.row.status }}</el-tag></template></el-table-column>
        <el-table-column v-if="showFinance" label="收款状态" min-width="125"><template #default="scope">{{ paymentNames[scope.row.paymentStatus] ?? scope.row.paymentStatus ?? '—' }}</template></el-table-column>
        <el-table-column label="库存占用" min-width="185"><template #default="scope">{{ reservationLabel(scope.row.reservationStatus, isAgent) }}</template></el-table-column>
        <el-table-column label="发货进度" min-width="140"><template #default="{ row }">{{ shipmentProgress(row) }}</template></el-table-column>
        <el-table-column prop="businessOwner" label="负责人" min-width="120" />
        <el-table-column label="创建时间" min-width="180"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
        <el-table-column label="修改时间" min-width="180"><template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template></el-table-column>
        <el-table-column prop="plannedShipDate" label="计划发货" width="120" /><el-table-column label="操作" width="90" fixed="right"><template #default="scope"><el-button link type="primary" @click="openDetail(scope.row)">查看详情</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[10, 20, 50, 100]" :total="total" layout="total, sizes, prev, pager, next" @current-change="load" @size-change="search" />
    </template>

    <el-dialog v-model="leaveDraftVisible" title="离开开单？" width="min(520px, 94vw)" append-to-body :close-on-click-modal="false" class="pi-leave-draft-dialog">
      <p>可以先保存草稿，下次从 PI 页面的“本机草稿”继续填写，未填完整也能保存。</p>
      <el-alert v-if="localSaveError" :title="localSaveError" type="error" :closable="false" />
      <template #footer><div class="pi-leave-draft-actions"><el-button @click="leaveDraftVisible = false">继续填写</el-button><el-button type="danger" plain @click="discardDraftAndLeave">放弃并离开</el-button><el-button type="primary" @click="saveDraftAndLeave">保存草稿并离开</el-button></div></template>
    </el-dialog>
    <el-dialog v-model="createVisible" :title="draftTitle" class="pi-draft-dialog" width="min(1280px, calc(100vw - 40px))" top="4vh" :close-on-click-modal="false" :close-on-press-escape="!actionBusy" :show-close="!actionBusy" :before-close="closeDraft" destroy-on-close @opened="focusDraftIssue">
      <template #header><div class="pi-draft-heading"><span class="pi-document-mark" aria-hidden="true">PI</span><div><span class="pi-eyebrow">PROFORMA INVOICE</span><h2>{{ draftTitle }}</h2><p>先把订单开好，后续收款与履约在详情中继续。</p></div><span class="pi-draft-badge">草稿 · 未发布</span></div></template>
      <nav class="pi-draft-steps" aria-label="PI 开单步骤">
        <button v-for="(step, index) in draftSteps" :key="step.title" type="button" :class="{ active: draftStep === index, passed: draftStep > index }" :aria-current="draftStep === index ? 'step' : undefined" :disabled="actionBusy" @click="goToDraftStep(index)"><span class="pi-step-number">{{ draftStep > index ? '✓' : '0' + (index + 1) }}</span><span><strong>{{ step.title }}</strong><small>{{ step.description }}</small></span><span v-if="index < 2" class="pi-step-arrow" aria-hidden="true">›</span></button>
      </nav>
      <div class="pi-draft-layout">
        <main ref="draftScroll" class="pi-draft-scroll" :aria-label="draftSteps[draftStep]?.title">
          <el-form label-position="top" class="pi-draft-form" :disabled="actionBusy || optionsBusy || restoringDraft" @submit.prevent="draftStep < 2 ? goToDraftStep(draftStep + 1) : saveCreate()">
            <section v-show="draftStep === 0" class="pi-draft-step">
              <div class="pi-step-heading"><span class="pi-eyebrow">STEP 01</span><h2>这份 PI，开给谁？</h2><p>选择客户后带入商务资料，再添加本次销售商品。</p></div>
              <section class="pi-form-card" data-draft-field="options">
                <div class="pi-section-heading"><h3>客户信息</h3><span class="pi-muted">带 * 的为必填项</span></div>
                <div class="pi-draft-grid">
                  <el-form-item label="客户" required data-draft-field="customer" :error="fieldError('customer')"><el-select v-model="draft.customerId" aria-label="客户" :loading="customerBusy || optionsBusy" @change="chooseCustomer" :disabled="editingRevision || fixedItems && !fromLocks" filterable placeholder="搜索或选择客户"><el-option v-for="customer in options.customers" :key="customer.id" :value="customer.id" :label="customer.code + ' · ' + customer.name" /></el-select></el-form-item>
                  <div v-if="draft.document.business" class="pi-draft-grid" data-draft-field="warehouse">
                  <el-form-item label="出库仓库（不同仓库分别开 PI）" required :error="fieldError('warehouse')"><el-select v-model="draft.document.business.warehouseId" :disabled="editingRevision && Boolean(detail?.document?.business)" filterable placeholder="选择本单出库仓库"><el-option v-for="warehouse in options.warehouses || []" :key="warehouse.id" :value="warehouse.id" :label="warehouse.name" /></el-select></el-form-item>
                  <el-form-item label="履约配置" :required="requiresProfile" data-draft-field="fulfillmentProfile" :error="fieldError('fulfillmentProfile')"><el-select :model-value="draft.document.business.fulfillmentProfileId" :clearable="!requiresProfile" filterable :disabled="editingRevision || optionsBusy" :placeholder="requiresProfile ? '请选择履约配置（必填）' : '历史单据可沿用原规则'" @change="chooseProfile"><el-option v-for="p in fulfillmentProfiles.filter(p => p.enabled || p.id === draft.document.business?.fulfillmentProfileId)" :key="p.id" :value="p.id" :label="`${p.name} · ${p.version}`" /></el-select></el-form-item>
                  <el-alert v-if="profilesError" :title="profilesError" type="error" :closable="false"><el-button text @click="loadProfiles">重试</el-button></el-alert>
                  <el-form-item v-if="draftProfile?.rules.paymentMode === 'CREDIT'" label="付款到期日" required data-draft-field="paymentDueDate" :error="fieldError('paymentDueDate')"><el-date-picker v-model="draft.document.business.paymentDueDate" value-format="YYYY-MM-DD" :disabled="editingRevision" /></el-form-item>
                  <p v-if="draftProfile" class="pi-field-help">{{ draftProfile.rules.paymentMode === 'CREDIT' ? '后付款须逐单授权；真实收款另行登记。' : '按约定收款金额保留库存与放行。' }}交付：{{ draftProfile.rules.deliveryMode === 'CUSTOMER_PICKUP' ? '客户货代提货' : '我方安排运输' }}。所需单据：{{ draftProfile.rules.requiredDocuments.join('、') || '按配置办理' }}。</p>
                  <el-form-item label="是否从国内出货" required><el-select v-model="draft.document.business.domesticShipment" :disabled="editingRevision && Boolean(detail?.document?.business)"><el-option :value="true" :label="draftProfile ? '是（合同要求以履约配置为准）' : '是，须办理 SAVA／银三环购销合同'" /><el-option :value="false" :label="draftProfile ? '否（合同要求以履约配置为准）' : '否，不适用国内购销合同'" /></el-select></el-form-item>
                </div><div class="pi-draft-grid pi-trade-fields"><el-form-item label="贸易类型" required><el-select v-model="draft.tradeType" aria-label="贸易类型" :disabled="editingRevision" @change="changeTradeType"><el-option value="DOMESTIC" label="内销" /><el-option value="EXPORT" label="外销" /></el-select></el-form-item><el-form-item label="币种" required data-draft-field="currency" :error="fieldError('currency')"><el-input v-model="draft.currencyCode" aria-label="币种" :disabled="editingRevision" maxlength="3" placeholder="CNY / USD / EUR" /></el-form-item></div>
                </div>
                <div v-if="customerBusy" class="pi-field-help" role="status">正在带入客户商务资料…</div>
                <el-alert v-if="customerLoadFailed" title="客户资料带入失败，当前内容暂不能提交" type="error" :closable="false"><el-button text :disabled="actionBusy" @click="draft.customerId && chooseCustomer(draft.customerId)">重试加载客户资料</el-button></el-alert>
                <section v-if="draft.customerId" class="pi-disclosure">
                  <div class="pi-section-heading"><div><h3>客户商务资料</h3><p>请核对并补齐必填资料，带 * 的为必填项。</p></div></div>
                  <div class="pi-draft-grid"><el-form-item v-for="[key, label, maxLength] in buyerFields" :key="key" :label="label" :required="isBuyerRequired(key)" :data-draft-field="key" :error="fieldError(key)" :class="{ 'pi-span-two': key === 'buyerAddress' }"><el-input v-model="draft.document[key]" :aria-label="label" :aria-required="isBuyerRequired(key)" :disabled="customerBusy" :type="key === 'buyerAddress' ? 'textarea' : 'text'" :rows="2" :maxlength="maxLength" /></el-form-item></div>
                </section>
                <div class="pi-disclosure">
                  <h3>订单来源与代理商</h3>
                  <el-form-item v-if="!fromLocks && !hasBoundOrders && draft.document.business" label="订单来源" required data-draft-field="orderSource" :error="fieldError('orderSource')">
                    <el-radio-group v-model="draft.document.business.orderSource" aria-label="订单来源" @change="changeOrderSource"><el-radio value="EXISTING_ORDER">关联已有订单</el-radio><el-radio value="NEW_BUSINESS">无原订单的新业务</el-radio></el-radio-group>
                  </el-form-item>
                  <el-alert v-if="fromLocks" title="将从所选锁单生成并关联履约订单，沿用原锁单的客户、代理商与数量。" type="info" :closable="false" />
                  <el-alert v-else-if="!hasBoundOrders && draft.document.business?.orderSource === 'NEW_BUSINESS'" title="仅用于没有原订单的新业务。预付达到收款门槛或后付款获批后，按所选仓库保留库存并生成关联订单；已有锁单或订单请使用对应入口。" type="warning" :closable="false" />
                  <div class="pi-draft-grid">
                    <el-form-item v-if="!fromLocks && (draft.sourceOrderId || !hasBoundOrders && draft.document.business?.orderSource === 'EXISTING_ORDER')" label="来源销售订单" required data-draft-field="sourceOrder" :error="fieldError('sourceOrder')"><el-select v-model="draft.sourceOrderId" aria-label="来源销售订单" clearable filterable :disabled="!draft.customerId || hasBoundOrders || editingRevision" placeholder="先选择客户，再选择原订单" @change="chooseSourceOrder"><el-option v-for="order in filteredOrders" :key="order.id" :value="order.id" :label="order.orderNo" /></el-select><p class="pi-field-help">自动带入原订单的代理商、SKU 和数量，复用原库存占用。</p></el-form-item>
                    <el-form-item label="所属代理商"><el-select v-model="draft.agentId" aria-label="所属代理商" :disabled="editingRevision || fixedItems && !fromLocks" clearable filterable placeholder="选择代理商；关联订单后自动带入"><el-option v-for="agent in options.agents" :key="agent.id" :value="agent.id" :label="agent.code + ' · ' + agent.name" /></el-select></el-form-item>
                  </div>
                </div>
              </section>
              <section class="pi-form-card" data-draft-field="items">
                <div class="pi-section-heading"><div><h3>商品明细 <span class="pi-count-badge">{{ draft.items.length }}</span></h3><p>{{ fromLocks ? '从下方锁定中选择商品，再填写单价。' : '选择 SKU 后填写数量与单价，图片在保存时带入。' }}</p></div><el-button :disabled="fixedItems || draft.items.length >= 100" @click="draft.items.push(newItem())">＋ 添加商品</el-button></div>
                <el-alert v-if="editingRevision && fixedItems" title="数量和移除商品在确认修订时生效，不能少于已发数量；新增 SKU 须选择来源锁定。" type="info" :closable="false" />
      <section data-draft-field="locks" class="pi-lock-selection" v-if="fromLocks || revisionFulfillment && canManageReservation">
        <p v-if="fromLocks" class="pi-hint">先选择客户及代理商，再选择来源锁定。本次会同时生成各SKU的履约订单和一份PI草稿；确认PI时一起确认履约订单，保留每笔锁定原截止。</p>
        <p v-if="revisionFulfillment" class="pi-hint">新增SKU或新增独立履约子单时选择同客户来源锁定，数量计入下方商品总数量；草稿不转换，确认修订才生效并沿用原截止时间。删除来源选择不会自动删除商品行。</p>
        <div class="pi-filters"><el-input v-model="lockKeyword" placeholder="按锁定单号或 SKU 搜索" /><el-button :disabled="!draft.customerId" :loading="lockBusy" @click="lockPage = 1; loadLocks()">查找来源锁定</el-button></div>
        <el-alert v-if="lockError" :title="lockError" type="error" :closable="false" />
        <el-table :data="lockRows" v-loading="lockBusy" empty-text="选择客户后查询可转换的SKU锁定">
      <RecordIdColumn />
          <el-table-column prop="lockNo" label="来源锁定" min-width="180" /><el-table-column prop="skuCode" label="SKU" /><el-table-column prop="remainingQty" label="剩余可转" width="100" /><el-table-column prop="expireTime" label="原截止" min-width="170" />
          <el-table-column label="选择" width="90"><template #default="{ row }"><el-button link :disabled="selectedLocks.some(item => item.id === row.id)" @click="addLock(row)">加入</el-button></template></el-table-column>
        </el-table>
        <el-pagination v-model:current-page="lockPage" :total="lockTotal" :page-size="20" layout="total, prev, pager, next" @current-change="loadLocks" />
        <el-table v-if="selectedLocks.length" :data="selectedLocks">
      <RecordIdColumn /><el-table-column prop="lockNo" label="已选来源" /><el-table-column prop="skuCode" label="SKU" /><el-table-column label="本次转换数量" width="180"><template #default="{ row }"><el-input-number v-model="row.quantity" :min="1" :max="row.remainingQty" :precision="0" @change="syncLockItems" /></template></el-table-column><el-table-column label="操作" width="80"><template #default="{ row }"><el-button link type="danger" @click="removeLock(row.id)">移除</el-button></template></el-table-column></el-table>
      </section>
                <div v-if="!draft.items.length" class="pi-inline-empty">还没有商品，请先选择来源锁定。</div>
                <article v-for="(item, index) in draft.items" :key="item.key" class="pi-item-card">
                  <div class="pi-item-heading"><span>商品 {{ String(index + 1).padStart(2, '0') }}</span><el-button text type="danger" :disabled="fixedItems && !editingRevision || draft.items.length === 1" :aria-label="'移除第' + (index + 1) + '行商品'" @click="removeItem(index)">移除</el-button></div>
                  <el-form-item label="SKU / 商品 / 规格" required :data-draft-field="'sku-' + item.key" :error="fieldError('sku-' + item.key)"><el-select v-model="item.skuId" @change="item.imageAttachmentId = undefined" :disabled="fixedItems" filterable :aria-label="'第' + (index + 1) + '行 SKU'" placeholder="搜索或选择 SKU"><el-option v-for="sku in options.skus" :key="sku.id" :value="sku.id" :disabled="sku.enabled === false || draft.items.some(other => other.key !== item.key && other.skuId === sku.id)" :label="sku.skuCode + ' · ' + sku.productName + (sku.specification ? ' · ' + sku.specification : '')" /></el-select></el-form-item>
                  <div class="pi-item-values">
                    <el-form-item label="数量" required :data-draft-field="'quantity-' + item.key" :error="fieldError('quantity-' + item.key)"><el-input-number v-model="item.quantity" :disabled="fixedItems && !editingRevision" :aria-label="'第' + (index + 1) + '行数量'" :min="1" :max="2147483647" :precision="0" controls-position="right" /></el-form-item>
                    <el-form-item label="单位" :data-draft-field="'unit-' + item.key" :error="fieldError('unit-' + item.key)"><el-input v-model="item.unit" maxlength="32" :aria-label="'第' + (index + 1) + '行单位'" /></el-form-item>
                    <el-form-item :label="'单价 / ' + draft.currencyCode.toUpperCase()" required :data-draft-field="'price-' + item.key" :error="fieldError('price-' + item.key)"><el-input v-model="item.unitPrice" :aria-label="'第' + (index + 1) + '行单价'" inputmode="decimal" placeholder="填写实际单价" /></el-form-item>
                    <div class="pi-item-total"><span>行金额</span><strong>{{ lineTotal(item) == null ? '待填写' : fromCents(lineTotal(item)!) }}</strong></div>
                  </div>
                  <details class="pi-line-adjustment" :data-draft-field="'adjustment-' + item.key" :open="cents(item.discountAmount) !== 0n || cents(item.taxAmount) !== 0n"><summary>行折扣与税费 <small>{{ cents(item.discountAmount) === 0n && cents(item.taxAmount) === 0n ? '默认无调整，按需展开' : '已填写调整金额' }}</small></summary><div class="pi-draft-grid"><el-form-item label="行折扣"><el-input v-model="item.discountAmount" :aria-label="'第' + (index + 1) + '行折扣'" inputmode="decimal" /></el-form-item><el-form-item label="行税费"><el-input v-model="item.taxAmount" :aria-label="'第' + (index + 1) + '行税费'" inputmode="decimal" /></el-form-item></div></details>
                </article>
                <div class="pi-catalog-search"><el-input v-model="optionKeyword" aria-label="搜索 PI 基础资料" placeholder="找不到资料？输入客户、SKU 或订单编号" clearable @keydown.enter.prevent="loadOptions" /><el-button :loading="optionsBusy" @click="loadOptions">查找资料</el-button></div><p class="pi-field-help">每类最多显示 200 条；搜索会保留已选择的项目。</p>
                <el-alert v-if="optionsError" :title="optionsError" type="error" :closable="false"><el-button text @click="loadOptions">重试加载</el-button></el-alert>
              </section>
            </section>
            <section v-show="draftStep === 1" class="pi-draft-step">
              <div class="pi-step-heading"><span class="pi-eyebrow">STEP 02</span><h2>把交易约定说清楚。</h2><p>模板负责固定资料，你只需填写这笔订单的实际约定。</p></div>
              <section class="pi-form-card" data-draft-field="template"><PiTemplatePicker :trade-type="draft.tradeType" :document="draft.document" :can-manage="canManageTemplates" :disabled="actionBusy || Boolean(draftProfile)" :refresh-key="templateRefreshKey" @select="chooseTemplate" @manage="templateVisible = true" /></section>
              <section class="pi-form-card" data-draft-field="bank"><div class="pi-section-heading"><h3>收款信息 · {{ draft.document.business?.collectionEntity || '待选择' }}</h3></div><p class="pi-field-help">模板带入账户后可按本单更新；保存为本单快照，已确认单据须通过修订调整。</p><div class="pi-draft-grid"><el-form-item label="收款银行" required><el-input v-model="draft.document.bankName" maxlength="200" /></el-form-item><el-form-item label="收款账户" required><el-input v-model="draft.document.bankAccount" maxlength="200" /></el-form-item><el-form-item label="收款人 / 公司全称" required><el-input v-model="draft.document.beneficiary" maxlength="200" /></el-form-item></div></section>
              <section class="pi-form-card"><div class="pi-section-heading"><div><h3>付款约定</h3><p>收款门槛不会自动填写比例；请按实际约定确认。</p></div></div>
                <el-form-item label="付款条款" required data-draft-field="paymentTerms" :error="fieldError('paymentTerms')"><el-input v-model="draft.document.paymentTerms" aria-label="付款条款" type="textarea" :rows="3" maxlength="2000" placeholder="填写双方约定的付款方式、时间及条件" /></el-form-item>
                <div class="pi-draft-grid"><el-form-item label="定金约定金额" required data-draft-field="deposit" :error="fieldError('deposit')"><el-input v-model="draft.depositRequiredAmount" aria-label="定金约定金额" inputmode="decimal" placeholder="无门槛须明确填写 0.00"><template #append>{{ draft.currencyCode.toUpperCase() }}</template></el-input><span class="pi-field-help">预付按本金额保留库存；后付款按授权保留，到账另行登记。</span></el-form-item><el-form-item label="发货放行门槛" required data-draft-field="shipment" :error="fieldError('shipment')"><el-input v-model="draft.shipmentRequiredAmount" aria-label="发货放行门槛" inputmode="decimal" placeholder="无门槛须明确填写 0.00"><template #append>{{ draft.currencyCode.toUpperCase() }}</template></el-input><span class="pi-field-help">不低于库存门槛，且不超过 PI 总金额。</span></el-form-item></div>
              </section>
              <section class="pi-form-card"><div class="pi-section-heading"><h3>交付安排</h3><span class="pi-muted">带 * 的为必填项</span></div>
                <el-form-item label="交付条款" required data-draft-field="deliveryTerms" :error="fieldError('deliveryTerms')"><el-input v-model="draft.document.deliveryTerms" aria-label="交付条款" type="textarea" :rows="3" maxlength="2000" placeholder="填写交货地点、交付责任及其他约定" /></el-form-item>
                <div class="pi-draft-grid"><el-form-item label="计划发货日期"><el-date-picker v-model="draft.plannedShipDate" aria-label="计划发货日期" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" /></el-form-item><el-form-item label="运输方式" required data-draft-field="transportMode" :error="fieldError('transportMode')"><el-input v-model="draft.document.transportMode" aria-label="运输方式" maxlength="100" placeholder="填写实际运输方式" /></el-form-item><el-form-item label="开单日期" required data-draft-field="issueDate" :error="fieldError('issueDate')"><el-date-picker v-model="draft.document.issueDate" aria-label="开单日期" type="date" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="有效期至" required data-draft-field="validUntil" :error="fieldError('validUntil')"><el-date-picker v-model="draft.document.validUntil" aria-label="有效期至" type="date" value-format="YYYY-MM-DD" /></el-form-item><el-form-item v-if="draft.tradeType === 'EXPORT' || draft.document.tradeTerms" label="贸易术语（外销）" :required="draft.tradeType === 'EXPORT'" data-draft-field="tradeTerms" :error="fieldError('tradeTerms')" class="pi-span-two"><el-input v-model="draft.document.tradeTerms" aria-label="贸易术语" maxlength="100" placeholder="按实际约定填写" /></el-form-item><el-form-item label="收货地址" required data-draft-field="shippingAddress" :error="fieldError('shippingAddress')" class="pi-span-two"><el-input v-model="draft.document.shippingAddress" aria-label="收货地址" type="textarea" :rows="2" maxlength="1000" placeholder="从客户资料带入，可针对本单修改" /></el-form-item></div>
              </section>
              <section class="pi-form-card"><details class="pi-disclosure pi-fees-disclosure" data-draft-field="fees" :open="feesExpanded" @toggle="feesExpanded = ($event.target as HTMLDetailsElement).open"><summary><span><strong>整单折扣与费用</strong><small>有运费、税费或额外折扣时填写</small></span><span>按需展开</span></summary><div class="pi-draft-grid"><el-form-item label="全单追加折扣"><el-input v-model="draft.discountAmount" aria-label="全单追加折扣" inputmode="decimal" /></el-form-item><el-form-item label="全单追加税费"><el-input v-model="draft.taxAmount" aria-label="全单追加税费" inputmode="decimal" /></el-form-item><el-form-item label="运费"><el-input v-model="draft.freightAmount" aria-label="运费" inputmode="decimal" /></el-form-item><el-form-item label="其他费用"><el-input v-model="draft.otherFeeAmount" aria-label="其他费用" inputmode="decimal" /></el-form-item></div></details><el-form-item label="备注（选填）"><el-input v-model="draft.remark" aria-label="PI 备注" type="textarea" :rows="2" maxlength="1000" show-word-limit /></el-form-item></section>
            </section>
            <section v-if="draftStep === 2" class="pi-draft-step">
              <div class="pi-step-heading"><span class="pi-eyebrow">STEP 03</span><h2>最后，核对一下。</h2><p>本次仅保存草稿，不会发布单据或触发收款、放行。</p></div>
              <section class="pi-form-card"><div class="pi-section-heading"><h3>客户与商品</h3><el-button text type="primary" @click="goToDraftStep(0)">返回修改</el-button></div><dl class="pi-detail-grid"><div><dt>客户</dt><dd>{{ selectedCustomer?.name }}</dd></div><div><dt>贸易 / 币种</dt><dd>{{ draft.tradeType === 'EXPORT' ? '外销' : '内销' }} · {{ draft.currencyCode.toUpperCase() }}</dd></div><div><dt>所属代理商</dt><dd>{{ selectedAgent?.name || '未关联' }}</dd></div><div><dt>来源订单</dt><dd>{{ options.orders.find(order => order.id === draft.sourceOrderId)?.orderNo || (fromLocks ? '由来源锁定生成' : hasBoundOrders ? '已关联履约订单' : draft.document.business?.orderSource === 'NEW_BUSINESS' ? '新业务，收款达标后自动生成' : '请选择来源订单') }}</dd></div></dl><div class="pi-review-items"><article v-for="item in draft.items" :key="item.key"><div><strong>{{ options.skus.find(sku => sku.id === item.skuId)?.skuCode }}</strong><span>{{ options.skus.find(sku => sku.id === item.skuId)?.productName }}</span><small>{{ item.quantity }} {{ item.unit }} × {{ money(item.unitPrice) }} · 折扣 {{ money(item.discountAmount) }} · 税费 {{ money(item.taxAmount) }}</small></div><strong>{{ lineTotal(item) == null ? '—' : fromCents(lineTotal(item)!) }}</strong></article></div><p v-if="selectedLocks.length" class="pi-field-help">关联 {{ selectedLocks.length }} 笔来源锁定，沿用原截止时间。</p></section>
              <section class="pi-form-card"><div class="pi-section-heading"><h3>交易与交付</h3><el-button text type="primary" @click="goToDraftStep(1)">返回修改</el-button></div><dl class="pi-detail-grid"><div><dt>模板</dt><dd>{{ draft.document.templateCode }} · {{ draft.document.templateVersion }}</dd></div><div><dt>卖方</dt><dd>{{ draft.document.sellerName }}</dd></div><div><dt>收款人</dt><dd>{{ draft.document.beneficiary }}</dd></div><div><dt>收款银行 / 账号</dt><dd>{{ draft.document.bankName }}<br />{{ draft.document.bankAccount }}</dd></div><div><dt>库存保留门槛</dt><dd>{{ draft.currencyCode.toUpperCase() }} {{ money(draft.depositRequiredAmount) }}</dd></div><div><dt>发货放行门槛</dt><dd>{{ draft.currencyCode.toUpperCase() }} {{ money(draft.shipmentRequiredAmount) }}</dd></div><div class="pi-span-two"><dt>付款条款</dt><dd>{{ draft.document.paymentTerms }}</dd></div><div class="pi-span-two"><dt>交付条款</dt><dd>{{ draft.document.deliveryTerms }}</dd></div><div><dt>计划发货 / 运输方式</dt><dd>{{ draft.plannedShipDate || '待补充' }} · {{ draft.document.transportMode || '待补充' }}</dd></div><div><dt>开单日期 / 有效期</dt><dd>{{ draft.document.issueDate || '待补充' }} / {{ draft.document.validUntil || '待补充' }}</dd></div><div v-if="draft.tradeType === 'EXPORT'"><dt>贸易术语</dt><dd>{{ draft.document.tradeTerms || '待补充' }}</dd></div><div class="pi-span-two"><dt>收货地址</dt><dd>{{ draft.document.shippingAddress || '待补充' }}</dd></div><div v-if="draft.remark" class="pi-span-two"><dt>备注</dt><dd>{{ draft.remark }}</dd></div></dl></section>
              <div class="pi-save-notice"><strong>保存后还可以继续编辑</strong><p>客户发布、收款确认与发货放行仍须在 PI 详情中分别操作。</p></div>
            </section>
          </el-form>
        </main>
        <aside class="pi-draft-summary" aria-label="PI 金额摘要">
          <div class="pi-summary-caption"><span>本单概览</span><span class="pi-draft-dot">{{ localSaveError ? '保存失败' : localSavedAt ? '已存本机' : '未保存' }}</span></div>
          <h3>{{ selectedCustomer?.name || '等待选择客户' }}</h3><p>{{ draft.tradeType === 'EXPORT' ? '外销' : '内销' }} · {{ draft.items.filter(item => item.skuId).length }} 款商品 · 数量 {{ draftQuantity }}</p>
          <div class="pi-summary-total"><span>预计总金额 / {{ draft.currencyCode.toUpperCase() || '币种待填' }}</span><strong>{{ preview && draft.items.length ? fromCents(preview.total) : '—' }}</strong></div>
          <dl class="pi-summary-breakdown"><div><dt>商品金额</dt><dd>{{ preview ? fromCents(preview.subtotal) : '—' }}</dd></div><div><dt>折扣合计</dt><dd>{{ preview ? '− ' + fromCents(preview.discount) : '—' }}</dd></div><div><dt>税费合计</dt><dd>{{ preview ? fromCents(preview.tax) : '—' }}</dd></div><div><dt>运费</dt><dd>{{ preview ? fromCents(preview.freight) : '—' }}</dd></div><div><dt>其他费用</dt><dd>{{ preview ? fromCents(preview.otherFees) : '—' }}</dd></div></dl>
          <div class="pi-summary-template"><span>单据模板</span><strong>{{ draft.document.templateCode ? draft.document.templateCode + ' · ' + draft.document.templateVersion : '第二步选择' }}</strong><small>卖方与银行资料随模板固定带入。</small></div>
          <p class="pi-summary-tip">只录入实际业务约定。未填写的金额不参与预估，不会自动补成交价或付款比例。</p>
        </aside>
      </div>
      <template #footer>
        <div class="pi-draft-footer">
          <div v-if="draftValidation || createError" class="pi-draft-error" role="alert">{{ draftValidation?.message || createError }}</div>
          <div v-if="localSaveError" class="pi-draft-error" role="alert">{{ localSaveError }}<el-button v-if="localSaveError.includes('其他页面')" text @click="saveLocalDraftCopy">另存为新草稿</el-button></div>
          <div class="pi-local-save-status" role="status">{{ localSaveStatus }}<span v-if="localSavedAt"> · {{ new Date(localSavedAt).toLocaleTimeString() }}</span> · 当前浏览器、当前账号可恢复</div>
          <div class="pi-footer-row">
            <el-button :disabled="actionBusy || restoringDraft" @click="closeDraft()">关闭</el-button>
            <span class="pi-footer-note">第 {{ draftStep + 1 }} / 3 步</span>
            <div class="pi-footer-actions">
              <el-button :disabled="actionBusy || restoringDraft" @click="saveLocalDraft">保存本机草稿</el-button>
              <el-button v-if="draftStep > 0" :disabled="actionBusy" @click="goToDraftStep(draftStep - 1)">上一步</el-button>
              <el-button v-if="draftStep < 2" type="primary" :disabled="optionsBusy || customerBusy || actionBusy || restoringDraft" @click="goToDraftStep(draftStep + 1)">{{ draftStep === 0 ? '下一步：交易与交付' : '下一步：核对信息' }} <span aria-hidden="true">→</span></el-button>
              <el-button v-else type="primary" :loading="actionBusy" :disabled="optionsBusy || customerBusy || customerLoadFailed || Boolean(optionsError) || restoringDraft" @click="saveCreate">确认并保存 PI</el-button>
            </div>
          </div>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="detailVisible" :title="detail ? `${detail.piNo} · V${detail.revision}` : 'PI 详情'" width="min(1100px, 96vw)" :close-on-click-modal="false">
      <div v-loading="detailBusy" class="pi-detail"><el-alert v-if="operationError" :title="operationError" type="error" :closable="false" show-icon />
        <template v-if="detail"><div class="pi-detail-heading"><div><el-tag>{{ stateNames[detail.status] ?? detail.status }}</el-tag><strong>{{ detail.customerName }}</strong><span>{{ detail.tradeType === 'EXPORT' ? '外销' : '内销' }} · {{ detail.currencyCode }}</span></div><strong class="pi-grand-total">{{ money(detail.totalAmount) }}</strong></div>
          <div class="pi-actions">
            <PiRevisions v-if="internal" :pi="detail" :can-write="canWrite" @select="openDetail" />
            <el-button v-if="canWrite && detail.status === 'DRAFT'" :disabled="actionBusy" @click="openEdit">编辑草稿</el-button>
            <el-button v-if="canWrite && ['DRAFT', 'CONFIRMED'].includes(detail.status)" type="danger" plain :disabled="actionBusy" @click="cancelPi">取消 PI</el-button>
            <el-button v-if="canRenewReservation" :disabled="actionBusy" type="warning" plain @click="openReservation()">核对库存并重新占用</el-button>
            <el-button v-if="canExport" :loading="actionBusy" @click="download('pdf')">下载 PDF</el-button>
            <el-button v-if="canExport" :loading="actionBusy" @click="download('xlsx')">下载 Excel</el-button>
          </div>
          <el-alert v-if="detail.reservationStatus === 'EXPIRED_REQUIRES_REACQUIRE'" title="来源订单库存占用已到期" type="warning" :closable="false" show-icon>
            {{ isAgent ? '请联系业务人员核对库存并处理占用。' : '到账记录保留；请在流程中重试按仓锁库。系统重新核对指定仓库和全局可用库存，历史缺少精确SKU的子单须先核对。' }}
            <span v-if="internal && !canManageReservation">请交由有订单及代理锁库维护权限的业务人员处理。</span>
          </el-alert>
          <el-alert v-if="internal && detail.reservationStatus === 'CREDIT_HELD'" title="后付款授权保留" description="库存已按授权保留；本状态不代表实际收到款项，仍须按约定跟进收款。" type="info" :closable="false" />
          <el-alert v-if="internal && detail.reservationStatus === 'PAID_HELD'" title="收款达标保留，待履约或人工关闭" description="该占用不按原截止时间自动释放；本状态不代表已全额到账。" type="success" :closable="false" show-icon />
          <el-alert v-if="detail.reservationStatus === 'RELEASED'" title="来源订单库存占用已释放" description="当前来源占用不能继续用于发货放行，请联系业务人员处理。" type="warning" :closable="false" show-icon />
          <p v-if="internal && detail.reservationStatus === 'FULFILLED'" class="pi-hint">来源订单已履约。需要撤销出库时，请先核对订单占用及可撤销条件。<a v-if="allowed('ORDER_READ')" href="/orders">查看销售订单</a></p>
          <el-alert v-if="detail.status === 'CANCELLED' && detail.remark?.startsWith('自动取消：')" title="PI已自动取消" :description="detail.remark" type="warning" :closable="false" show-icon />
          <p class="pi-hint">库存占用：{{ reservationLabel(detail.reservationStatus, isAgent) }}</p>
          <el-descriptions :column="2" border><el-descriptions-item label="所属代理商">{{ detail.agentName || '—' }}</el-descriptions-item><el-descriptions-item label="计划发货">{{ detail.plannedShipDate || '—' }}</el-descriptions-item><el-descriptions-item label="创建时间">{{ detail.createdAt }}</el-descriptions-item><el-descriptions-item label="备注">{{ detail.remark || '—' }}</el-descriptions-item></el-descriptions>
          <p v-if="detailProfile">履约配置：{{ detailProfile.name }} · {{ detailProfile.version }}；收款主体：{{ detailProfile.collectionEntity }}<span v-if="detail.document?.business?.paymentDueDate">；付款到期：{{ detail.document.business.paymentDueDate }}</span></p>
          <PiWorkflowPanel v-if="internal" :workflow="workflow" :rules="detailProfile?.rules" :busy="workflowBusy" :error="workflowError" :action-busy="workflowActionBusy || actionBusy" :can-write="canWrite" :can-pay="canPay" :can-release="canRelease" :can-logistics="canLogistics" :can-after-sales="canAfterSales" :pi-id="detail.id" :logistics="detail.logistics" :domestic-shipment="detail.document?.business?.domesticShipment" :pi-status="detail.status" :currency-code="detail.currencyCode" :gate-met="gateMet" :reservation-blocked="reservationBlocked" @action="handleWorkflowAction" @complete="completeWorkflow" @production-update="recordProductionUpdate" @after-sales="emit('navigate', 'after-sales')" />
          <el-tabs v-model="detailTab"><el-tab-pane label="商品与金额" name="items"><el-table :data="detail.items" empty-text="暂无商品">
      <RecordIdColumn /><el-table-column prop="skuCode" label="SKU" min-width="140" /><el-table-column label="图片" width="100"><template #default="scope"><PiItemImage v-if="scope.row.imageAttachmentId" :key="`${detail.id}-${scope.row.id}`" :pi-id="detail.id" :item-id="scope.row.id" /><span v-else>未记录</span></template></el-table-column><el-table-column prop="productName" label="商品" min-width="140" /><el-table-column prop="specification" label="规格" min-width="140" /><el-table-column prop="quantity" label="数量" width="80" /><el-table-column prop="unit" label="单位" width="80" /><el-table-column label="单价" min-width="110"><template #default="scope">{{ money(scope.row.unitPrice) }}</template></el-table-column><el-table-column label="折扣" min-width="100"><template #default="scope">{{ money(scope.row.discountAmount) }}</template></el-table-column><el-table-column label="税费" min-width="100"><template #default="scope">{{ money(scope.row.taxAmount) }}</template></el-table-column><el-table-column label="金额" min-width="120"><template #default="scope">{{ money(scope.row.lineTotal) }}</template></el-table-column></el-table><div class="pi-totals"><span>商品金额 <b>{{ money(detail.subtotal) }}</b></span><span>折扣合计 <b>− {{ money(detail.discountAmount) }}</b></span><span>税费合计 <b>{{ money(detail.taxAmount) }}</b></span><span>运费 <b>{{ money(detail.freightAmount) }}</b></span><span>其他费用 <b>{{ money(detail.otherFeeAmount ?? '0.00') }}</b></span><span>总金额 <b>{{ detail.currencyCode }} {{ money(detail.totalAmount) }}</b></span></div></el-tab-pane>
            <el-tab-pane v-if="detail.fulfillment?.length" label="履约订单" name="fulfillment">
              <template v-if="internal && detail.revisionSources?.length">
                <h3>本次修订新增来源</h3><p class="pi-hint">草稿保存的是待转换选择；确认修订后显示关联子单。来源截止和数量保留供追溯。</p>
                <el-table :data="detail.revisionSources">
      <RecordIdColumn prop="lockId" label="来源锁定 ID" /><el-table-column prop="lockNo" label="来源锁定" min-width="180" /><el-table-column prop="skuCode" label="SKU" min-width="120" /><el-table-column prop="quantity" label="转换数量" width="100" /><el-table-column prop="expiresAt" label="来源截止" min-width="180" /><el-table-column label="关联子单" min-width="180"><template #default="{ row }">{{ row.orderId ? detail.fulfillment.find(line => line.orderId === row.orderId)?.orderNo || row.orderId : '待确认转换' }}</template></el-table-column></el-table>
              </template>
              <el-table :data="detail.fulfillment">
      <RecordIdColumn prop="orderId" label="订单 ID" /><el-table-column prop="orderNo" label="订单" min-width="200" /><el-table-column prop="skuCode" label="SKU" min-width="120" /><el-table-column prop="orderedQty" label="约定" width="70" /><el-table-column prop="shippedQty" label="已发" width="70" /><el-table-column prop="closedQty" label="关闭" width="70" /><el-table-column prop="remainingQty" label="待发" width="70" /><el-table-column label="库存占用" min-width="180"><template #default="{ row }">{{ reservationLabel(row.reservationStatus, isAgent) }}</template></el-table-column><el-table-column v-if="canManageReservation" label="操作" width="130"><template #default="{ row }"><el-button v-if="['TIMED_ACTIVE', 'EXPIRED_REQUIRES_REACQUIRE'].includes(row.reservationStatus)" link :disabled="actionBusy" @click="openReservation(row.orderId)">核对并重新占用</el-button></template></el-table-column></el-table>
            </el-tab-pane>
            <el-tab-pane label="真实出库批次" name="batches"><el-table :data="detail.dispatchBatches || []" empty-text="尚无实际出库批次">
      <RecordIdColumn /><el-table-column prop="batchNo" label="出库批次" min-width="240" /><el-table-column prop="createdAt" label="出库时间" min-width="180" /><el-table-column prop="originalQty" label="原出库" width="85" /><el-table-column prop="activeQty" label="有效数量" width="90" /><el-table-column label="来源" min-width="150"><template #default="{ row }">{{ row.sourceType === 'LEGACY_SINGLE' ? '历史单车记录（非原合批）' : row.sourceType === 'PI_COMMAND' ? 'PI整批出库' : '订单批量出库' }}</template></el-table-column><el-table-column label="状态" width="100"><template #default="{ row }">{{ row.status === 'COMPLETED' ? '已出库' : row.status === 'CANCELLED' ? '已撤销' : '部分撤销' }}</template></el-table-column><el-table-column label="车辆" width="90"><template #default="{ row }"><el-button link @click="openBatch(row.id)">查看</el-button></template></el-table-column></el-table></el-tab-pane>
            <el-tab-pane label="单据资料与条款" name="document"><el-descriptions :column="2" border><el-descriptions-item v-for="[key, label] in documentLabels" :key="key" :label="label"><span class="pi-prewrap">{{ detail.document?.[key] || '—' }}</span></el-descriptions-item></el-descriptions></el-tab-pane>
            <el-tab-pane v-if="showFinance" label="收款与放行" name="payment"><div class="pi-tab-toolbar"><strong>到账记录</strong><el-button v-if="canPay && ['CONFIRMED', 'RELEASED'].includes(detail.status)" type="primary" plain :disabled="actionBusy" @click="openPayment">追加到账</el-button></div><el-descriptions :column="2" border><el-descriptions-item label="累计到账">{{ detail.currencyCode }} {{ money(detail.paidAmount) }}</el-descriptions-item><el-descriptions-item label="收款状态">{{ paymentNames[detail.paymentStatus ?? ''] ?? detail.paymentStatus ?? '—' }}</el-descriptions-item><el-descriptions-item label="定金约定金额">{{ money(detail.depositRequiredAmount) }}</el-descriptions-item><el-descriptions-item label="发货放行门槛">{{ money(detail.shipmentRequiredAmount) }}</el-descriptions-item><el-descriptions-item label="放行状态">{{ detail.releaseStatus === 'RELEASED' ? '已放行' : '未放行' }}</el-descriptions-item><el-descriptions-item label="放行时间">{{ detail.releasedAt || '—' }}</el-descriptions-item></el-descriptions><p v-if="canRelease && detail.status === 'CONFIRMED' && !gateMet" class="pi-hint">{{ detailProfile?.rules.paymentMode === 'CREDIT' ? '请先由有权限人员批准本单后付款安排。' : '累计到账尚未达到发货门槛，暂不能放行。' }}</p><el-table :data="detail.payments" empty-text="尚无到账记录">
      <RecordIdColumn /><el-table-column label="状态" width="100"><template #default="scope">{{ scope.row.status === 'REVERSED' ? '已撤回登记' : '已确认' }}</template></el-table-column><el-table-column label="收款方式" min-width="140"><template #default="scope">{{ paymentMethods[scope.row.paymentMethod] || '未记录' }} {{ scope.row.paymentMethodNote }}</template></el-table-column><el-table-column prop="recordedAt" label="登记时间" min-width="170" /><el-table-column prop="confirmedBy" label="登记人ID" width="100" /><el-table-column prop="reversalReason" label="撤回原因" min-width="180" /><el-table-column prop="reversedAt" label="撤回时间" min-width="170" /><el-table-column prop="reversedBy" label="撤回人ID" width="100" /><el-table-column v-if="canReversePayment" label="更正" width="110"><template #default="scope"><el-button v-if="scope.row.status === 'CONFIRMED'" link type="danger" @click="openPaymentReversal(scope.row)">撤回错误登记</el-button></template></el-table-column><el-table-column prop="receivedAt" label="到账时间" min-width="170" /><el-table-column label="金额" min-width="130"><template #default="scope">{{ scope.row.currencyCode }} {{ money(scope.row.amount) }}</template></el-table-column><el-table-column prop="remark" label="说明" min-width="220" /><el-table-column label="收款凭证" width="120"><template #default="scope"><el-button link type="primary" @click="openReceipt(scope.row)">{{ canPay ? '查看 / 上传' : '查看' }}</el-button></template></el-table-column></el-table></el-tab-pane>
            <el-tab-pane label="物流信息" name="logistics"><div class="pi-tab-toolbar"><strong>运输记录</strong><el-button v-if="canLogistics && ['CONFIRMED', 'RELEASED'].includes(detail.status)" type="primary" plain :disabled="actionBusy" @click="openLogistics()">新增物流记录</el-button></div><el-table :data="detail.logistics" empty-text="尚无物流记录">
      <RecordIdColumn /><el-table-column label="附件" width="90"><template #default="{ row }"><el-button link @click="openLogisticsAttachment(row)">查看附件</el-button></template></el-table-column><el-table-column label="来源依据" min-width="130"><template #default="{ row }">{{ row.linkType === 'ACTUAL_BATCH' ? '真实出库批次' : row.linkType === 'LEGACY_UNLINKED' ? '历史手工记录，未关联' : '未关联出库的计划' }}</template></el-table-column><el-table-column prop="shippedQtySnapshot" label="确认发运台数" width="120" /><el-table-column prop="batchNo" label="物流批次" min-width="150" /><el-table-column prop="carrier" label="承运商" min-width="130" /><el-table-column prop="trackingNo" label="运单号" min-width="170" /><el-table-column label="状态" width="100"><template #default="scope">{{ logisticsStates[scope.row.status] || scope.row.status }}</template></el-table-column><el-table-column prop="shippedAt" label="发货时间" min-width="180" /><el-table-column prop="remark" label="备注" min-width="180" /><el-table-column v-if="internal" label="操作" width="210"><template #default="{ row }"><el-button v-if="canLogistics && row.status !== 'SHIPPED'" link :disabled="actionBusy" @click="openLogistics(row)">编辑 / 确认发运</el-button><el-button v-if="canLogistics && row.status !== 'WITHDRAWN'" link :disabled="actionBusy" @click="withdrawLogistics(row)">撤回</el-button><el-button link @click="openLogisticsHistory(row)">历史</el-button></template></el-table-column></el-table></el-tab-pane>
            <el-tab-pane v-if="canExport" label="导出任务" name="exports"><PiExportTask v-if="detailTab === 'exports'" :pi-id="detail.id" :can-retry="internal && canExport" :status="detail.status" /></el-tab-pane>
            <el-tab-pane label="业务单据附件" name="confirmations"><PiConfirmationAttachments v-if="detailTab === 'confirmations'" :pi-id="detail.id" :can-upload="canWrite && ['DRAFT', 'CONFIRMED', 'RELEASED'].includes(detail.status)" /></el-tab-pane>
          </el-tabs></template>
      </div><template #footer><el-button @click="detailVisible = false">关闭</el-button></template>
    </el-dialog>

    <el-dialog v-model="logisticsAttachmentVisible" title="物流附件" width="min(850px, 95vw)" :close-on-click-modal="false">
      <PiLogisticsAttachments v-if="detail && logisticsAttachment && logisticsAttachmentVisible" :pi-id="detail.id" :logistics-id="logisticsAttachment.id" :can-upload="canLogistics && ['PLANNED', 'SHIPPED'].includes(logisticsAttachment.status)" />
    </el-dialog>
    <PiReceiptAttachments v-if="showFinance && detail && receiptPayment" v-model="receiptVisible" :pi-id="receiptPayment.piId" :payment="receiptPayment" :can-upload="canPay && receiptPayment.status === 'CONFIRMED'" />
    <el-dialog v-model="reversalVisible" title="撤回错误到账登记" width="520px" :close-on-click-modal="false">
      <p>此操作更正错误登记，不办理退款。原记录和凭证保留，当前 PI 需重新核对并放行。已出库车辆和库存占用保持原状。</p>
      <el-input v-model="reversal.reason" type="textarea" maxlength="500" placeholder="说明哪笔登记有误及核对依据" />
      <el-alert v-if="reversalError" :title="reversalError" type="error" :closable="false" />
      <template #footer><el-button :disabled="actionBusy" @click="reversalVisible = false">取消</el-button><el-button type="danger" :loading="actionBusy" @click="savePaymentReversal">确认撤回登记</el-button></template>
    </el-dialog>
    <el-dialog v-model="logisticsHistoryVisible" title="物流修订历史" width="min(1000px, 94vw)"><el-alert v-if="logisticsHistoryError" :title="logisticsHistoryError" type="error" :closable="false" /><el-table :data="logisticsHistory" v-loading="logisticsHistoryBusy"><el-table-column prop="revision" label="版本" width="70" /><el-table-column prop="createdAt" label="记录时间" min-width="180" /><el-table-column label="动作"><template #default="{ row }">{{ ({ CREATE: '创建', UPDATE: '更新', WITHDRAW: '撤回', MIGRATED_CURRENT: '迁移当时快照' } as Record<string,string>)[row.action] || row.action }}</template></el-table-column><el-table-column prop="carrier" label="承运商" /><el-table-column prop="trackingNo" label="运单号" /><el-table-column label="状态"><template #default="{ row }">{{ logisticsStates[row.status] || row.status }}</template></el-table-column><el-table-column prop="shippedQtySnapshot" label="发运数量" /><el-table-column prop="revokeReason" label="撤回原因" min-width="200" /></el-table></el-dialog>
    <el-dialog v-model="batchVisible" title="出库批次车辆" width="min(1000px, 94vw)"><el-alert v-if="batchError" :title="batchError" type="error" :closable="false" /><el-table v-loading="batchBusy" :data="batchVehicles">
      <RecordIdColumn prop="stockOutId" label="出库记录 ID" /><el-table-column prop="vehicleQrCode" label="聚合码" min-width="240" show-overflow-tooltip /><el-table-column prop="frameSn" label="车架码" min-width="180" /><el-table-column prop="skuCode" label="SKU" /><el-table-column prop="orderNo" label="履约订单" min-width="180" /><el-table-column prop="stockOutNo" label="出库单" min-width="180" /><el-table-column label="状态"><template #default="{ row }">{{ row.status === 'COMPLETED' ? '已出库' : '已撤销' }}</template></el-table-column><el-table-column prop="cancelReason" label="撤销原因" /></el-table></el-dialog>
    <el-dialog v-model="reservationVisible" title="核对库存并重新占用" width="540px" :close-on-click-modal="false" :close-on-press-escape="!actionBusy">
      <p>提交时重新检查可用库存。已经到期的占用不会因登记收款而自动恢复；库存不足时本次操作会被拒绝。</p>
      <p class="pi-hint">如关联的已确认 PI 已达到保留库存门槛，成功占用后将按收款达标规则保留，待履约或人工关闭。</p>
      <el-form label-position="top" @submit.prevent="saveReservation"><el-form-item label="未达保留门槛时的占用时长（小时）" required><el-input-number v-model="reservation.hours" :min="1" :max="720" :precision="0" /></el-form-item><el-form-item label="重新占用原因" required><el-input v-model="reservation.reason" type="textarea" maxlength="500" show-word-limit /></el-form-item></el-form>
      <el-alert v-if="reservationError" :title="reservationError" type="error" :closable="false" show-icon />
      <template #footer><el-button :disabled="actionBusy" @click="reservationVisible = false">取消</el-button><el-button type="primary" :loading="actionBusy" @click="saveReservation">确认重新占用</el-button></template>
    </el-dialog>
    <el-dialog v-model="paymentVisible" title="登记到账" width="520px" :close-on-click-modal="false"><el-form label-position="top"><el-form-item label="到账金额" required><el-input v-model="payment.amount" inputmode="decimal"><template #append>{{ payment.currencyCode }}</template></el-input></el-form-item><el-form-item label="到账时间"><el-date-picker v-model="payment.receivedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="留空使用当前时间" /></el-form-item><el-form-item label="收款方式" required><el-select v-model="payment.paymentMethod"><el-option v-for="(label, key) in paymentMethods" :key="key" :value="key" :label="label" /></el-select></el-form-item><el-form-item v-if="payment.paymentMethod === 'OTHER'" label="其他方式说明" required><el-input v-model="payment.paymentMethodNote" maxlength="200" /></el-form-item><el-form-item label="到账说明"><el-input v-model="payment.remark" type="textarea" maxlength="500" placeholder="到账来源、银行流水说明等" /></el-form-item></el-form><el-alert v-if="paymentError" :title="paymentError" type="error" :closable="false" /><template #footer><el-button :disabled="actionBusy" @click="paymentVisible = false">取消</el-button><el-button type="primary" :loading="actionBusy" @click="savePayment">确认到账</el-button></template></el-dialog>
    <el-dialog v-model="releaseVisible" title="放行发货" width="520px" :close-on-click-modal="false"><p>请核对合同、指定仓库库存及收款凭证；已确认的每笔收款须在收款记录中上传凭证，再放行出库。</p><el-form label-position="top"><el-form-item label="放行说明" required><el-input v-model="releaseReason" type="textarea" maxlength="500" /></el-form-item></el-form><el-alert v-if="releaseError" :title="releaseError" type="error" :closable="false" /><template #footer><el-button :disabled="actionBusy" @click="releaseVisible = false">取消</el-button><el-button type="success" :loading="actionBusy" @click="saveRelease">确认放行</el-button></template></el-dialog>
    <el-dialog v-model="logisticsVisible" title="登记物流批次" width="560px" :close-on-click-modal="false"><el-form label-position="top"><el-form-item label="真实出库批次"><el-select v-model="logistics.stockOutBatchId" clearable placeholder="确认发运时必选"><el-option v-for="batch in detail?.dispatchBatches || []" :key="batch.id" :value="batch.id" :disabled="batch.activeQty === 0" :label="`${batch.batchNo} · 有效 ${batch.activeQty} 台`" /></el-select></el-form-item><el-form-item label="物流批次 / 计划编号" required><el-input v-model="logistics.batchNo" :disabled="Boolean(logistics.stockOutBatchId)" maxlength="64" /></el-form-item><el-form-item label="物流状态" required><el-select v-model="logistics.status"><el-option value="PLANNED" label="计划中" /><el-option value="SHIPPED" label="已发货" :disabled="detail?.status !== 'RELEASED'" /></el-select></el-form-item><el-form-item label="承运商" :required="logistics.status === 'SHIPPED' && !customerArrangedLogistics"><el-input v-model="logistics.carrier" maxlength="128" /></el-form-item><el-form-item label="运单号" :required="logistics.status === 'SHIPPED' && !customerArrangedLogistics"><el-input v-model="logistics.trackingNo" maxlength="128" /></el-form-item><el-form-item label="备注"><el-input v-model="logistics.remark" type="textarea" maxlength="500" /></el-form-item></el-form><el-alert v-if="logisticsError" :title="logisticsError" type="error" :closable="false" /><template #footer><el-button :disabled="actionBusy" @click="logisticsVisible = false">取消</el-button><el-button type="primary" :loading="actionBusy" @click="saveLogistics">保存物流</el-button></template></el-dialog>
  </section>
</template>

<style scoped>
.pi-workspace{padding:28px 32px;min-width:0;max-width:1500px;margin:auto}.pi-heading{display:flex;justify-content:space-between;gap:20px;align-items:center;margin-bottom:24px}.pi-heading h2{margin:0 0 7px;font-size:24px}.pi-heading p,.pi-hint{color:#65758a;font-size:13px;line-height:1.7}.pi-heading p{margin:0}.pi-filters{display:flex;gap:12px;align-items:center;margin-bottom:18px}.pi-filters>.el-input{max-width:340px}.pi-filters>.el-select{width:160px}.el-pagination{margin-top:20px;justify-content:flex-end;flex-wrap:wrap}.el-alert{margin:12px 0}.pi-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:0 18px}.pi-form .el-select,.pi-form .el-date-editor,.el-form-item .el-date-editor{width:100%}.pi-form h3{font-size:16px;margin:22px 0 14px}.pi-items-scroll{overflow-x:auto;margin-bottom:12px}.pi-draft-items{min-width:1020px}.pi-draft-items th,.pi-draft-items td{padding:10px 6px}.pi-draft-items th:first-child{width:340px}.pi-draft-items .el-input-number{width:108px}.pi-draft-items .el-input{min-width:98px}.pi-draft-items .el-select{min-width:320px}.pi-amounts{margin-top:24px}.pi-preview{background:#f1f7fc;border-radius:8px;padding:14px 18px;display:flex;flex-direction:column;gap:7px;align-self:start}.pi-preview span{font-size:12px;color:#52687d}.pi-preview strong{font-size:23px;color:#1677c8}.pi-preview small{font-size:11px;line-height:1.6;color:#748196}.pi-detail{min-height:140px}.pi-detail-heading{display:flex;justify-content:space-between;align-items:center;gap:20px;margin:8px 0 18px}.pi-detail-heading>div{display:flex;gap:12px;flex-wrap:wrap;align-items:center}.pi-detail-heading span{font-size:13px;color:#65758a}.pi-grand-total{font-size:24px;color:#1677c8}.pi-actions{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:18px}.pi-actions .el-button+.el-button{margin-left:0}.pi-tab-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:12px}.pi-totals{display:flex;justify-content:flex-end;gap:22px;flex-wrap:wrap;background:#f6f9fc;padding:18px;margin-top:16px}.pi-totals span{display:flex;flex-direction:column;gap:8px;color:#65758a;font-size:12px}.pi-totals b{font-size:15px;color:#23364d}.pi-prewrap{white-space:pre-wrap;overflow-wrap:anywhere}.el-tabs{margin-top:18px}@media(max-width:900px){.pi-workspace{padding:22px 18px}.pi-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.pi-filters{flex-wrap:wrap}}@media(max-width:580px){.pi-grid{grid-template-columns:1fr}.pi-heading{align-items:flex-start;flex-direction:column}.pi-detail-heading{align-items:flex-start;flex-direction:column}.pi-filters>.el-input{max-width:none;width:100%}.pi-grand-total{font-size:21px}}
</style>
