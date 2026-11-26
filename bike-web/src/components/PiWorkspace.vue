<script setup lang="ts">
import { tr, trInline } from '../i18n/language'
import RecordIdColumn from './table/RecordIdColumn.vue'
import { isSelectablePiLayout } from '../pi-template-layouts'
import { createUuid } from '../utils/uuid'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { createPiLocalDraftStore, type DraftItem, type PiDraftForm, type PiLocalDraft, type PiLocalDraftContent } from '../pi-local-drafts'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { hasPermission, hasRole, type PermissionContext } from '../permissions'
import { piApi, reservationLabel, type Money, type PiCreate, type PiDocument, type PiOptions, type PiPayment, type PiView, type PiLockOption, type PiLockSummary, type PiLogistics, type PiWorkflow, type PiWorkflowComplete, type PiListQuery } from '../pi-api'
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
import PiRelatedActions, { type PiRelatedAction } from './PiRelatedActions.vue'
import PiWorkflowPanel from './PiWorkflowPanel.vue'
import PiManualWorkflowPanel from './PiManualWorkflowPanel.vue'
import SalesSettlementPanel from './SalesSettlementPanel.vue'
import PiFeeInput from './PiFeeInput.vue'
import PurchaseContractNumber from './PurchaseContractNumber.vue'
import '../styles/pi-draft.css'
import { salesScenarioDefaults, salesScenarioFilters, matchesSalesScenario, salesChannelLayoutName, type PiSalesScenario } from '../pi-sales-scenario'
import { clearSalesIntent, salesTypeLabel, type SalesIntent } from '../sales-routing'

const props = withDefaults(defineProps<{ context: (PermissionContext & { userType?: string; username?: string }) | null; initialKeyword?: string; europe?: boolean; salesScenario?: PiSalesScenario; allowCreate?: boolean; intent?: SalesIntent | null; genericEntry?: boolean; requestedDetailId?: number | null }>(), { allowCreate: true, intent: null, genericEntry: false })
const scenario = computed(() => props.salesScenario ? salesScenarioDefaults(props.salesScenario) : undefined)
const emit = defineEmits<{ navigate: [view: string]; detailRequested: [] }>()
const isAgent = computed(() => hasRole(props.context, 'WEB_AGENT') || props.context?.userType === 'AGENT')
const isPda = computed(() => hasRole(props.context, 'PDA_OPERATOR') || props.context?.userType === 'PDA_OPERATOR')
const internal = computed(() => !isAgent.value && !isPda.value)
const allowed = (permission: string) => internal.value && hasPermission(props.context, permission)
const canRead = computed(() => !isPda.value && (!props.europe || internal.value) && hasPermission(props.context, 'PI_READ'))
const canWrite = computed(() => allowed('PI_WRITE'))
const canManageTemplates = computed(() => allowed('PI_TEMPLATE_MANAGE'))
const templateVisible = ref(false), profilesVisible = ref(false)
const templateAdjustmentSource = ref<PiTemplate>()
function openTemplates(source?: PiTemplate) {
  if (!canManageTemplates.value || actionBusy.value) return
  templateAdjustmentSource.value = source
  templateVisible.value = true
}
const fulfillmentProfiles = ref<FulfillmentProfile[]>([]), profilesError = ref(''), profilesLoading = ref(false)
const profileDialogSeedId = ref<number>(), profileDialogWarehouseId = ref<number>(), profileDialogTradeType = ref<string>(), profileDialogAutoOpen = ref(false)
const profileRepairSeedId = ref<number>()
const requiresProfile = computed(() => editingId.value == null || Boolean(detail.value?.document?.business?.fulfillmentProfileId))
const draftProfile = computed(() => fulfillmentProfiles.value.find(p => p.id === draft.document.business?.fulfillmentProfileId))
const detailProfile = computed(() => fulfillmentProfiles.value.find(p => p.id === detail.value?.document?.business?.fulfillmentProfileId))
const detailIsChinaExport = computed(() => {
  const business = detail.value?.document?.business
  return detail.value?.tradeType === 'EXPORT' && !props.europe
    && (business?.salesChannel === 'CHINA_SALES'
      || !business?.salesChannel && business?.domesticShipment !== false)
})
const manualWorkflow = computed(() => detail.value?.document?.business?.workflowMode === 'USER_CONFIRMED')
let profileSelectionVersion = 0
async function loadProfiles() {
  if (!internal.value) return
  profilesLoading.value = true; profilesError.value = ''
  try { fulfillmentProfiles.value = await fulfillmentApi.list() } catch(e) { profilesError.value = message(e, '履约配置加载失败，请重试') }
  finally { profilesLoading.value = false }
}
function profileSupportsWarehouse(profile: FulfillmentProfile, warehouseId?: number | null) {
  return Boolean(warehouseId) && (!profile.rules.warehouseIds.length || profile.rules.warehouseIds.includes(warehouseId!))
}
async function chooseProfile(id?: number) {
  const selectionVersion = ++profileSelectionVersion, sessionId = localDraftId
  if (!draft.document.business) return
  const profile = fulfillmentProfiles.value.find(p => p.id === id)
  if (!profile) { draft.document.business.fulfillmentProfileId = null; draft.document.business.paymentDueDate = null; draft.document.business.collectionEntity = draft.tradeType === 'EXPORT' ? 'HONG_KONG' : 'GUANGTA'; return }
  const warehouseId = draft.document.business.warehouseId
  if (!warehouseId || profile.tradeType !== draft.tradeType || !profileSupportsWarehouse(profile, warehouseId)) {
    profileRepairSeedId.value = profile.id
    draft.document.business.fulfillmentProfileId = null
    createError.value = !warehouseId ? '请先选择出库仓库，再选择适用于该仓库的履约配置' : '该履约配置不适用于当前贸易类型或出库仓库，请选择兼容版本'
    return
  }
  optionsBusy.value = true; createError.value = ''
  try {
    let page = 1, found: PiTemplate | undefined
    while (!found) { const result = await piApi.templates(profile.tradeType, page++); found = result.records.find(t => t.templateCode === profile.templateCode && t.templateVersion === profile.templateVersion); if (found || !result.records.length || (page - 1) * 20 >= result.total) break }
    if (selectionVersion !== profileSelectionVersion || sessionId !== localDraftId || !createVisible.value) return
    if (!found) throw new Error('配置对应的模板未找到，请核对配置')
    draft.document.business.fulfillmentProfileId = id
    draft.document.business.collectionEntity = profile.collectionEntity
    if (profile.rules.paymentMode === 'CREDIT') draft.depositRequiredAmount = '0.00'
    profileRepairSeedId.value = undefined
    chooseTemplate(found)
  } catch(e) { if (selectionVersion === profileSelectionVersion && sessionId === localDraftId && createVisible.value) { createError.value = message(e, '选用配置失败'); draft.document.business.fulfillmentProfileId = null } }
  finally { if (selectionVersion === profileSelectionVersion && sessionId === localDraftId) optionsBusy.value = false }
}
const fixedDocumentFields = ['sellerName', 'sellerAddress', 'sellerContact', 'sellerCountry', 'sellerContactName', 'sellerPhone', 'sellerEmail', 'bankName', 'bankAccount', 'beneficiary', 'templateCode', 'templateVersion', 'bankAddress', 'swiftCode', 'bankCode', 'branchCode', 'sellerRegistrationNo', 'sellerVatNo']
function chooseTemplate(value: PiTemplate) {
  if (value.tradeType !== draft.tradeType || !isSelectablePiLayout(value.layoutCode)) return
  for (const key of fixedDocumentFields as (keyof PiTemplate)[]) Object.assign(draft.document, { [key]: value[key] ?? '' })
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
const europeWarehouseId = ref<number | undefined>()
const europeWarehouses = ref<Awaited<ReturnType<typeof piApi.warehouseOptions>>>([])
const europeWarehousesBusy = ref(false), europeWarehouseError = ref(''), europePreferenceError = ref('')
const europeWarehouse = computed(() => europeWarehouses.value.find(item => item.id === europeWarehouseId.value))
const europeScopeReady = computed(() => !props.europe || Boolean(europeWarehouse.value))
const canCreateInScope = computed(() => props.allowCreate !== false && (!props.europe || europeWarehouse.value?.status === 'ENABLED'))
function europePreferenceKey() { return `bike:europe-warehouse:${encodeURIComponent(props.context?.username || '')}` }
let europeWarehouseLoadVersion = 0
async function loadEuropeWarehouses() {
  if (!props.europe || !internal.value || !canRead.value) return
  const version = ++europeWarehouseLoadVersion
  const preferenceKey = europePreferenceKey()
  europeWarehousesBusy.value = true; europeWarehouseError.value = ''
  try {
    const warehouses = await piApi.warehouseOptions()
    if (version !== europeWarehouseLoadVersion || preferenceKey !== europePreferenceKey()) return
    europeWarehouses.value = warehouses
    try {
      const saved = Number(localStorage.getItem(preferenceKey))
      europeWarehouseId.value = europeWarehouses.value.some(item => item.id === saved) ? saved : undefined
    } catch { europePreferenceError.value = '无法读取本机仓库偏好，请重新选择出货仓。' }
    refreshLocalDrafts(); await load()
  } catch (error) { if (version === europeWarehouseLoadVersion) europeWarehouseError.value = message(error, '出货仓加载失败，请重试') }
  finally { if (version === europeWarehouseLoadVersion) europeWarehousesBusy.value = false }
}
function selectEuropeWarehouse() {
  europePreferenceError.value = ''
  try { localStorage.setItem(europePreferenceKey(), String(europeWarehouseId.value)) }
  catch { europePreferenceError.value = '本次选择已生效，但无法保存到本机；下次进入需要重新选择。' }
  rows.value = []; total.value = 0
  refreshLocalDrafts(); search()
}
const selectedWarehouseId = computed(() => draft.document.business?.warehouseId)
const selectedWarehouse = computed(() => options.value.warehouses?.find(warehouse => warehouse.id === selectedWarehouseId.value))
const selectableProfiles = computed(() => fulfillmentProfiles.value.filter(profile =>
  (profile.enabled || profile.id === draft.document.business?.fulfillmentProfileId) &&
  profile.tradeType === draft.tradeType && profileSupportsWarehouse(profile, selectedWarehouseId.value)))
const showProfileGap = computed(() => Boolean(
  requiresProfile.value && selectedWarehouseId.value && !editingRevision.value &&
  !profilesLoading.value && !profilesError.value && !selectableProfiles.value.length))
const profileRepairSource = computed(() => fulfillmentProfiles.value.find(profile => profile.id === profileRepairSeedId.value && profile.tradeType === draft.tradeType)
  || (draftProfile.value?.tradeType === draft.tradeType ? draftProfile.value : undefined)
  || fulfillmentProfiles.value.find(profile => profile.enabled && profile.tradeType === draft.tradeType))
function changeWarehouse(warehouseId?: number) {
  if (!draft.document.business) return
  const profile = draftProfile.value
  if (!warehouseId || profile && !profileSupportsWarehouse(profile, warehouseId)) {
    if (profile) profileRepairSeedId.value = profile.id
    draft.document.business.fulfillmentProfileId = null
    draft.document.business.paymentDueDate = null
    draft.document.business.collectionEntity = draft.tradeType === 'EXPORT' ? 'HONG_KONG' : 'GUANGTA'
    if (profile && warehouseId) ElMessage.warning(tr('仓库已切换，原履约配置不适用，已清除；请选用或发布适用当前仓库的版本'))
  }
}
function openProfiles() {
  profileDialogSeedId.value = undefined; profileDialogWarehouseId.value = undefined
  profileDialogTradeType.value = undefined; profileDialogAutoOpen.value = false; profilesVisible.value = true
}
function openProfileRepair() {
  profileDialogSeedId.value = profileRepairSource.value?.id
  profileDialogWarehouseId.value = selectedWarehouseId.value ?? undefined
  profileDialogTradeType.value = draft.tradeType
  profileDialogAutoOpen.value = true
  profilesVisible.value = true
}
function resetProfileDialogContext() {
  profileDialogSeedId.value = undefined; profileDialogWarehouseId.value = undefined
  profileDialogTradeType.value = undefined; profileDialogAutoOpen.value = false
}
async function handleProfilePublished(profile: FulfillmentProfile) {
  await loadProfiles()
  const warehouseId = selectedWarehouseId.value
  if (createVisible.value && warehouseId && profile.tradeType === draft.tradeType && profileSupportsWarehouse(profile, warehouseId)) {
    await chooseProfile(profile.id)
    if (draft.document.business?.fulfillmentProfileId === profile.id) {
      profilesVisible.value = false
      ElMessage.success(tr('新履约配置已带入当前 PI 草稿，可以继续开单'))
    }
  }
}
const reportRefresh = ref(0), exportBusy = ref(false)
const showFinance = computed(() => allowed('PI_FINANCE_READ') || canPay.value || canRelease.value || canReversePayment.value)
const rows = ref<PiView[]>([]), total = ref(0), page = ref(1), pageSize = ref(20)
const keyword = ref(props.initialKeyword ?? ''), status = ref(''), busy = ref(false), loadError = ref('')
const hasCombinedFilters = computed(() => props.genericEntry && internal.value && !props.europe && !props.salesScenario)
const appliedKeyword = ref(keyword.value.trim())
const listScope = ref<'daily' | 'custom'>(appliedKeyword.value ? 'custom' : 'daily')
const monthShortcut = ref('')
const chosenMonth = ref<string | null>(null)
const selectedPiStatuses = ref<string[]>([])
const selectedPaymentStatuses = ref<string[]>([])
const fulfillmentCompleted = ref<boolean>()
const monthShortcuts = [
  { value: '', label: '不限' },
  { value: 'CURRENT', label: '本月' },
  { value: 'PREVIOUS', label: '上月' },
]
const fulfillmentOptions = [
  { value: undefined, label: '不限' },
  { value: false, label: '未完成' },
  { value: true, label: '已完成' },
]
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
const lockSummary = ref<PiLockSummary | null>(null)
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
const fixedLayoutName = computed(() => salesChannelLayoutName(draft.document.business?.salesChannel || (!editingRevision.value ? scenario.value?.salesChannel || (props.europe ? 'CZECH_PI' : undefined) : undefined)))
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
  try {
    const result = localStore().list()
    localDrafts.value = result.records.filter(saved => matchesSalesScenario(saved.content.form, props.salesScenario)
      && (!props.europe || europeWarehouseId.value != null && saved.content.form.document.business?.warehouseId === europeWarehouseId.value))
    localDraftError.value = result.errors.join('；')
  }
  catch { localDraftError.value = '无法读取本机草稿，请检查浏览器存储设置' }
}
function beginLocalDraft() {
  localDraftId = createUuid(); localDraftOwner = props.context?.username || ''
  activeLocalDraft = null; lastLocalContent = ''; localSavedAt.value = ''; localSaveError.value = ''
  profileRepairSeedId.value = undefined
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
function saveLocalDraft() { if (persistLocalDraft(true)) ElMessage.success(tr('草稿已保存到本机，可稍后继续填写')) }
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
    await ElMessageBox.confirm(tr('删除后无法恢复这份本机草稿。'), tr('删除本机草稿？'), { type: 'warning', confirmButtonText: tr('删除'), cancelButtonText: tr('保留') })
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
    if (!matchesSalesScenario(content.form, props.salesScenario))
      throw new Error('这份草稿的销售类型已变更，请在对应销售入口或全部／历史 PI 中继续填写')
    if (props.europe && content.form.document.business?.warehouseId !== europeWarehouseId.value)
      throw new Error('这份草稿的出货仓已变更，请在对应仓库或 PI 与履约入口继续填写')
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
  ['buyerCountry', '买方国家 / 地区', 100], ['buyerVatNo', '买方 VAT 号（选填）', 100], ['businessOwner', '业务归属', 150],
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
  const previousProfileId = draft.document.business?.fulfillmentProfileId ?? undefined
  for (const key of fixedDocumentFields) Object.assign(draft.document, { [key]: '' })
  if (draft.document.business) { draft.document.business.collectionEntity = draft.tradeType === 'EXPORT' ? 'HONG_KONG' : 'GUANGTA'; draft.document.business.fulfillmentProfileId = null }
  profileRepairSeedId.value = previousProfileId
  if (hadTemplate) ElMessage.info(tr('贸易类型已切换，请在第二步重新选用匹配模板'))
}
watch(templateVisible, (visible, previous) => { if (previous && !visible && createVisible.value) templateRefreshKey.value++ })
function fieldError(field: string) { return draftValidation.value?.field === field ? tr(draftValidation.value.message) : undefined }
watch(draft, () => {
  if (!draftValidation.value) return
  const current = stepIssue(draftValidation.value.step)
  if (!current || current.field !== draftValidation.value.field) draftValidation.value = null
}, { deep: true })
function stepIssue(step: number): { step: number; field: string; message: string } | null {
  const issue = (field: string, message: string) => ({ step, field, message })
  if (step === 0) {
    if (!matchesSalesScenario(draft, props.salesScenario)) return issue('warehouse', '单据的贸易类型或销售入口与当前页面不一致，请从对应入口办理')
    if (optionsError.value) return issue('options', '基础资料尚未加载成功，请重试')
    if (!draft.document.business?.warehouseId) return issue('warehouse', '请先选择出库仓库，不同仓库分别建立 PI')
    if (requiresProfile.value && !draft.document.business?.fulfillmentProfileId) return issue('fulfillmentProfile', selectableProfiles.value.length ? '请选择适用于当前仓库和贸易类型的履约配置' : '当前仓库没有可用履约配置，请先发布适用版本')
    if (draft.document.business.fulfillmentProfileId && !draftProfile.value) return issue('fulfillmentProfile', '请先成功加载本单履约配置')
    if (draftProfile.value && draftProfile.value.tradeType !== draft.tradeType) return issue('fulfillmentProfile', '当前履约配置与贸易类型不一致，请重新选择适用版本')
    if (draftProfile.value && !profileSupportsWarehouse(draftProfile.value, draft.document.business.warehouseId)) return issue('fulfillmentProfile', '当前履约配置不适用于所选仓库，请选择或发布兼容版本')
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
    const deposit = cents(draft.depositRequiredAmount), shipment = cents(draft.shipmentRequiredAmount.trim() || '0')
    if (deposit == null) return issue('deposit', '请明确填写库存保留门槛；无需门槛时填写 0.00')
    if (shipment == null) return issue('shipment', '请输入有效金额，最多16位整数、两位小数')
    if (deposit > expected.total) return issue('deposit', '库存保留门槛不能超过总金额')
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
// 销售入口只能关联同一销售类型的订单；待选类型订单须先在订单办理中选择类型。
const scenarioSalesType = computed(() => draft.document.business?.salesChannel === 'CHINA_SALES' ? 'EXPORT' : draft.document.business?.salesChannel === 'CZECH_PI' ? 'EUROPE' : undefined)
const filteredOrders = computed(() => [...new Map(options.value.orders.filter(order => order.customerId === draft.customerId
  && (!scenarioSalesType.value || editingId.value != null || order.salesType === scenarioSalesType.value)).map(order => [order.id, order])).values()])
const hiddenTypeOrders = computed(() => scenarioSalesType.value && editingId.value == null ? new Set(options.value.orders.filter(order => order.customerId === draft.customerId && order.salesType !== scenarioSalesType.value).map(order => order.id)).size : 0)
function chooseSourceOrder(id?: number) {
  if (!id) return
  const sources = options.value.orders.filter(order => order.id === id)
  const source = sources[0]
  if (!source) return
  draft.agentId = source.agentId ?? undefined
  draft.plannedShipDate = source.plannedShipDate || ''
  if (draft.document.business) draft.document.business.orderSource = 'EXISTING_ORDER'
  if (source.agentId && !options.value.agents.some(agent => agent.id === source.agentId))
    options.value.agents.push({ id: source.agentId, code: '', name: source.agentName || '原订单所属客户' })
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
    // 客户未设置偏好币种时保留销售入口默认币种（外销 USD、欧洲 EUR）。
    draft.currencyCode = customer.preferredCurrency || scenario.value?.currencyCode || 'CNY'
  } catch (error) { if (version === customerLoadVersion) { customerLoadFailed.value = true; createError.value = message(error, '客户商务资料带入失败，请重新选择客户') } }
  finally { if (version === customerLoadVersion) customerBusy.value = false }
}
watch(() => draft.customerId, () => { if (!restoringDraft.value && !initializingDraft && editingId.value == null && !filteredOrders.value.some(order => order.id === draft.sourceOrderId)) draft.sourceOrderId = undefined })
// 加入锁定时自动带入其代理商，此时不应清空已选锁定。
let agentFromLock = false
watch([() => draft.customerId, () => draft.agentId], () => {
  if (agentFromLock) { agentFromLock = false; return }
  if (restoringDraft.value || initializingDraft) return
  lockVersion++; lockRows.value = []; lockTotal.value = 0; lockPage.value = 1; selectedLocks.value = []
  if (fromLocks.value) { draft.items = []; void loadLocks() }
})
async function loadLocks() {
  if ((!fromLocks.value && !revisionFulfillment.value) || !draft.customerId) return
  const version = ++lockVersion; lockBusy.value = true; lockError.value = ''; lockSummary.value = null
  try {
    // 新建时未选代理商：查该客户全部代理商下的锁定（含无代理商），加入后自动带入代理商。
    const anyAgent = fromLocks.value && !revisionFulfillment.value && !draft.agentId && !selectedLocks.value.length
    const found = await piApi.lockOptions({ customerId: draft.customerId, agentId: draft.agentId, keyword: lockKeyword.value.trim() || undefined, page: lockPage.value, anyAgent: anyAgent || undefined })
    if (version === lockVersion) { lockRows.value = found.records; lockTotal.value = found.total }
    // 一条都查不到时，再统计该客户锁定为什么不能用（到期 / 已转完 / 其他代理商），失败不影响主流程。
    if (version === lockVersion && found.total === 0) {
      try {
        const summary = await piApi.lockOptionSummary({ customerId: draft.customerId, agentId: draft.agentId, anyAgent: anyAgent || undefined })
        if (version === lockVersion) lockSummary.value = summary
      } catch { /* 原因统计仅作提示 */ }
    }
  } catch (error) { if (version === lockVersion) lockError.value = message(error, '来源锁定加载失败') }
  finally { if (version === lockVersion) lockBusy.value = false }
}
const lockEmptyReasons = computed(() => {
  const summary = lockSummary.value
  if (!summary) return []
  const reasons: string[] = []
  const keyword = lockKeyword.value.trim()
  if (keyword) reasons.push(`按「${keyword}」没有搜到，可清空搜索框再查`)
  if (!Number(summary.totalCount)) { reasons.push('该客户还没有做过 SKU 锁定'); return reasons }
  if (Number(summary.expiredCount)) reasons.push(`${summary.expiredCount} 条已到期${summary.lastExpiredAt ? `（最近一条 ${String(summary.lastExpiredAt).replace('T', ' ').slice(0, 16)} 到期）` : ''}，需到锁定页重新锁定或延期`)
  if (Number(summary.finishedCount)) reasons.push(`${summary.finishedCount} 条已全部转单或已释放`)
  if (Number(summary.otherAgentCount)) reasons.push(tr('{count} 条属于其他客户，可清空上方「所属客户」后再查', { count: summary.otherAgentCount }))
  return reasons
})
// 没有可用锁定时，直接改成普通新建 PI：保留客户、代理商和已填的单据信息，商品改为手动选 SKU。
function switchToNewPi() {
  if (!fromLocks.value || revisionFulfillment.value) return
  lockVersion++; lockBusy.value = false
  fromLocks.value = false; selectedLocks.value = []; lockRows.value = []; lockTotal.value = 0; lockSummary.value = null
  conversionKey = ''; conversionFingerprint = ''
  draft.items = [newItem()]
  ElMessage.success(tr('已改为新建 PI，请在「商品明细」中选择 SKU、填写数量和单价'))
}
function syncLockItems() {
  if (revisionFulfillment.value) return
  const grouped = new Map<number, number>()
  for (const lock of selectedLocks.value) grouped.set(lock.skuId, (grouped.get(lock.skuId) || 0) + lock.quantity)
  draft.items = [...grouped].map(([skuId, quantity]) => ({ ...(draft.items.find(item => item.skuId === skuId) || newItem()), skuId, quantity }))
}
function addLock(lock: PiLockOption, quantity = lock.remainingQty) {
  if (selectedLocks.value.some(item => item.id === lock.id)) return
  if (fromLocks.value && !revisionFulfillment.value) {
    const lockAgent = lock.agentId ?? undefined
    const first = selectedLocks.value[0]
    if (first && (first.agentId ?? undefined) !== lockAgent) { ElMessage.warning(tr('同一份 PI 只能合并同一客户的锁定，请分开生成')); return }
    if ((draft.agentId ?? undefined) !== lockAgent) {
      if (draft.agentId) { ElMessage.warning(tr('该锁定属于其他客户，请先清空「所属客户」再加入')); return }
      if (lockAgent && !options.value.agents.some(agent => agent.id === lockAgent)) options.value.agents.push({ id: lockAgent, code: '', name: lock.agentName || '锁定所属客户' })
      agentFromLock = true; draft.agentId = lockAgent
    }
  }
  selectedLocks.value.push({ ...lock, quantity })
  if (!options.value.skus.some(item => item.id === lock.skuId)) options.value.skus.push({ id: lock.skuId, skuCode: lock.skuCode, productName: lock.productName, modelId: lock.modelId })
  if (revisionFulfillment.value && !draft.items.some(item => item.skuId === lock.skuId)) draft.items.push({ ...newItem(), skuId: lock.skuId, quantity: lock.remainingQty })
  syncLockItems()
}
async function loadLockBatch(lockId: number): Promise<PiLockOption[]> {
  type LockSource = PiLockOption & { productModelId: number; lockQty: number }
  const response = await api.get<{ data: LockSource[] }>(`/api/inventory-locks/${lockId}/domestic-order-sources`)
  return response.data.data.map(source => ({ ...source, modelId: source.productModelId, remainingQty: source.lockQty }))
}
async function selectLock(lock: PiLockOption) {
  if (lockBusy.value) return
  if (fromLocks.value && selectedLocks.value.some(item =>
    (item.lockRequestId != null || lock.lockRequestId != null) && item.lockRequestId !== lock.lockRequestId)) {
    ElMessage.warning(tr('请按同一次预锁提交分别转单'))
    return
  }
  if (lock.lockRequestId == null) { addLock(lock); return }
  const version = ++lockVersion
  lockBusy.value = true
  try {
    const batch = await loadLockBatch(lock.id)
    if (version !== lockVersion || !createVisible.value) return
    for (const source of batch) addLock(source)
  } catch (cause) {
    if (version === lockVersion) ElMessage.error(tr(message(cause, '锁定来源加载失败')))
  } finally {
    if (version === lockVersion) lockBusy.value = false
  }
}
function removeLock(id: number) {
  const batchId = selectedLocks.value.find(item => item.id === id)?.lockRequestId
  selectedLocks.value = selectedLocks.value.filter(item => batchId == null ? item.id !== id : item.lockRequestId !== batchId)
  syncLockItems()
}
function removeItem(index: number) {
  const skuId = draft.items[index]?.skuId
  const sources = selectedLocks.value.filter(lock => lock.skuId === skuId)
  for (const source of sources) removeLock(source.id)
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
function listQuery(): PiListQuery {
  const scope = {
    ...salesScenarioFilters(props.salesScenario),
    warehouseId: props.europe ? europeWarehouseId.value : undefined,
  }
  if (!hasCombinedFilters.value) {
    return { ...scope, keyword: keyword.value.trim() || undefined, status: status.value || undefined }
  }
  const query = { ...scope, keyword: appliedKeyword.value || undefined }
  if (listScope.value === 'daily') return { ...query, dailyFollowUp: true }
  return {
    ...query,
    createdMonth: chosenMonth.value || monthShortcut.value || undefined,
    statuses: selectedPiStatuses.value.join(',') || undefined,
    paymentStatuses: showFinance.value ? selectedPaymentStatuses.value.join(',') || undefined : undefined,
    fulfillmentCompleted: fulfillmentCompleted.value,
  }
}

async function load() {
  if (!canRead.value) return
  if (!europeScopeReady.value) { ++listVersion; rows.value = []; total.value = 0; busy.value = false; return }
  const version = ++listVersion; busy.value = true; loadError.value = ''
  try { const result = await piApi.list({ ...listQuery(), page: page.value, pageSize: pageSize.value }); if (version === listVersion) { rows.value = result.records; total.value = result.total; reportRefresh.value++ } }
  catch (error) { if (version === listVersion) { rows.value = []; total.value = 0; loadError.value = message(error, 'PI 列表加载失败') } }
  finally { if (version === listVersion) busy.value = false }
}
function search() {
  appliedKeyword.value = keyword.value.trim()
  page.value = 1
  void load()
}
function resetList() {
  keyword.value = ''
  status.value = ''
  listScope.value = 'daily'
  monthShortcut.value = ''
  chosenMonth.value = null
  selectedPiStatuses.value = []
  selectedPaymentStatuses.value = []
  fulfillmentCompleted.value = undefined
  search()
}
function applyCustomFilters() {
  listScope.value = 'custom'
  search()
}
function selectMonth(shortcut: string) {
  chosenMonth.value = null
  monthShortcut.value = shortcut
  applyCustomFilters()
}
function selectChosenMonth() {
  monthShortcut.value = ''
  applyCustomFilters()
}
function selectStatuses(category: 'pi' | 'payment', value?: string) {
  const selection = category === 'pi' ? selectedPiStatuses : selectedPaymentStatuses
  selection.value = value === undefined ? []
    : selection.value.includes(value) ? selection.value.filter(status => status !== value)
      : [...selection.value, value]
  applyCustomFilters()
}
function selectFulfillment(value?: boolean) {
  fulfillmentCompleted.value = value
  applyCustomFilters()
}
async function exportList() {
  if (exportBusy.value || !europeScopeReady.value) return
  exportBusy.value = true; loadError.value = ''
  try {
    const blob = await piApi.exportList(listQuery())
    const url = URL.createObjectURL(blob), link = document.createElement('a'); link.href = url; link.download = props.europe ? '欧洲订单.xlsx' : 'PI列表.xlsx'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
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
  try { const piId = detail.value.id; workflow.value = await piApi.completeWorkflow(piId, stepCode, body); if (stepCode === 'INVENTORY_RESERVED') detail.value = await piApi.get(piId); ElMessage.success(tr('已保存，流程已进入下一步')) }
  catch (error) { workflowError.value = message(error, 'PI流程步骤保存失败') }
  finally { workflowActionBusy.value = false }
}
async function recordProductionUpdate(body: PiWorkflowComplete) {
  if (!detail.value || !canWrite.value || workflowActionBusy.value) return
  workflowActionBusy.value = true; workflowError.value = ''
  try {
    workflow.value = await piApi.recordProductionUpdate(detail.value.id, body)
    ElMessage.success(tr(body.productionCompleted ? '生产跟进已完成' : '本次生产跟进已保存'))
  } catch (error) { workflowError.value = message(error, '生产跟进保存失败') }
  finally { workflowActionBusy.value = false }
}
const workflowRelatedActions = computed<PiRelatedAction[]>(() => [
  { key: 'logistics', label: '去维护物流信息', steps: ['TRACKING_RECORDED', 'CUSTOMER_RECEIVED', 'WAREHOUSE_ARRIVED', 'CASE_CLOSED'] },
  { key: 'confirmations', label: '去维护业务单据附件', steps: ['DOCUMENTS_READY', 'PURCHASE_CONTRACT_READY', 'DISPATCH_SHEET_SENT', 'CI_SENT', 'RD_SENT', 'RD_SIGNED', 'CMR_COLLECTED', 'CASE_CLOSED'], unavailable: canWrite.value ? undefined : '请联系业务经办人维护附件。' },
  { key: 'payment', label: '去维护收款记录', steps: ['PAYMENT_CONFIRMED', 'PAYMENT_SETTLED', 'STOCK_DISPATCHED', 'RELEASE_PI', 'CASE_CLOSED'], unavailable: showFinance.value ? undefined : '请联系财务人员维护收款记录。' },
  ...(detail.value?.fulfillment?.length && reservationBlocked.value ? [{ key: 'fulfillment', label: '去维护库存占用', steps: ['INVENTORY_RESERVED', 'STOCK_DISPATCHED', 'RELEASE_PI'] }] : []),
  { key: 'inventory', label: '去查看 SKU 库存', steps: ['INVENTORY_RESERVED'], href: '/sku-inventory', unavailable: allowed('INVENTORY_READ') ? undefined : '请联系仓库人员核对库存。' },
  { key: 'stock-out', label: '去查看出库记录', steps: ['STOCK_DISPATCHED'], href: '/stock-out', unavailable: allowed('STOCK_READ') ? undefined : '请联系仓库人员办理出库。' },
])
async function openRelatedSection(key: string) {
  const action = workflowRelatedActions.value.find(item => item.key === key)
  if (!detail.value || !action || action.unavailable || action.href || actionBusy.value || workflowActionBusy.value) return
  if (releaseVisible.value) relatedReturn.value = 'release'
  releaseVisible.value = false
  detailTab.value = key
  await nextTick()
  detailTabsElement.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
const relatedReturn = ref<'release' | ''>('')
function resumeRelatedForm() { releaseVisible.value = true; relatedReturn.value = ''; void loadReleaseReceipts() }
watch(detailVisible, visible => { if (!visible) relatedReturn.value = '' })
const detailTabsElement = ref<HTMLElement>()
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
  if (!canWrite.value || !canCreateInScope.value || restoringDraft.value || createVisible.value) return
  initializingDraft = true
  customerLoadVersion++; customerBusy.value = false; customerLoadFailed.value = false
  editingId.value = null
  fromLocks.value = mode === 'locks'; selectedLocks.value = []; conversionKey = ''; conversionFingerprint = ''
  Object.assign(draft, { customerId: undefined, agentId: undefined, sourceOrderId: undefined, tradeType: 'DOMESTIC', currencyCode: 'CNY', plannedShipDate: '', discountAmount: '0.00', taxAmount: '0.00', freightAmount: '0.00', otherFeeAmount: '0.00', depositRequiredAmount: '', shipmentRequiredAmount: '', remark: '', document: emptyDocument(), items: [newItem()] })
  if (props.europe && draft.document.business) {
    draft.tradeType = 'EXPORT'; draft.currencyCode = 'EUR'; draft.depositRequiredAmount = '0.00'
    draft.document.business.warehouseId = europeWarehouseId.value!; draft.document.business.domesticShipment = false
    draft.document.business.workflowMode = 'USER_CONFIRMED'; draft.document.business.collectionEntity = 'HONG_KONG'
  }
  if (scenario.value && draft.document.business) {
    draft.tradeType = scenario.value.tradeType
    draft.currencyCode = scenario.value.currencyCode
    draft.document.business.domesticShipment = scenario.value.domesticShipment
    draft.document.business.salesChannel = scenario.value.salesChannel
    if (scenario.value.salesChannel === 'CHINA_SALES') draft.document.productCategory = 'EBIKE and FRAME'
    draft.document.business.collectionEntity = draft.tradeType === 'EXPORT' ? 'HONG_KONG' : 'GUANGTA'
    draft.document.business.orderSource = 'NEW_BUSINESS'
  }
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
    agents: current.agentId ? [{ id: current.agentId, code: '', name: current.agentName || '所属客户' }] : [],
    orders: current.sourceOrderId ? [{ id: current.sourceOrderId, orderNo: '原来源订单', customerId: current.customerId, agentId: current.agentId, status: 'CONFIRMED' }] : [],
    skus: current.items.map(item => ({ id: item.skuId, productModelId: item.productModelId, skuCode: item.skuCode, productName: item.productName, specification: item.specification })),
  }
  selectedLocks.value = (current.revisionSources || []).filter(source => !source.orderId).map(source => ({ id: source.lockId, skuId: source.skuId, quantity: source.quantity, remainingQty: source.quantity, lockNo: source.lockNo, skuCode: source.skuCode, productName: source.productName, modelId: current.items.find(item => item.skuId === source.skuId)?.productModelId || 0, expireTime: source.expiresAt }))
  resetDraftWizard(); beginLocalDraft(); createError.value = ''; optionKeyword.value = ''; createVisible.value = true
  await nextTick(); initializingDraft = false; await loadOptions()
}
function payload(): PiCreate {
  if (props.europe && !editingRevision.value && draft.document.business && !draft.document.business.salesChannel)
    draft.document.business.salesChannel = 'CZECH_PI'
  if (!draft.customerId) throw new Error('请选择客户')
  if (draft.document.issueDate && draft.document.validUntil && draft.document.validUntil < draft.document.issueDate) throw new Error('有效期不能早于开单日期')
  if (!draft.document.sellerName.trim() || !draft.document.paymentTerms.trim() || !draft.document.deliveryTerms.trim() || !draft.document.templateCode.trim() || !draft.document.templateVersion.trim()) throw new Error('请填写卖方名称、付款条款、交付条款、模板名称及版本')
  if (!/^[A-Z]{3}$/.test(draft.currencyCode.trim().toUpperCase())) throw new Error('币种需填写 3 位代码，如 CNY、USD、EUR')
  const expected = preview.value
  if (!expected || expected.total < 0n || expected.discount > expected.subtotal) throw new Error('请检查商品金额、折扣、税费和运费；金额最多保留两位小数')
  const deposit = cents(draft.depositRequiredAmount), shipment = cents(draft.shipmentRequiredAmount.trim() || '0')
  if (deposit == null) throw new Error('请明确填写库存保留门槛；无需门槛时填写 0.00')
  if (shipment == null) throw new Error('发货放行门槛请输入有效金额，最多16位整数、两位小数')
  if (deposit > expected.total) throw new Error('库存保留门槛不能超过总金额')
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
    catch { ElMessage.warning(tr('PI 已保存，但本机草稿未能清除，请在草稿列表中删除对应记录，避免重复提交')) }
    activeLocalDraft = null; refreshLocalDrafts()
    await load(); await openDetail(created); ElMessage.success(tr('PI 草稿已保存'))
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
    const answer = await ElMessageBox.prompt(tr(prompt), tr('取消 PI'), { confirmButtonText: tr('确认取消'), cancelButtonText: tr('保留单据'), inputType: 'textarea', inputValidator: (value: string) => value.trim().length > 0 && value.length <= 500 ? true : tr('请输入 1–500 字原因') })
    actionBusy.value = true; operationError.value = ''; detail.value = await piApi.cancel(detail.value.id, answer.value.trim()); await load(); ElMessage.success(tr('PI 已取消'))
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
    await ElMessageBox.confirm(tr('确认后，单据将向所属客户发布。请核对商品、金额、双方资料和条款。'), tr('确认 PI'), { confirmButtonText: tr('确认发布'), cancelButtonText: tr('返回核对'), type: 'warning' })
    actionBusy.value = true; operationError.value = ''; detail.value = await piApi.confirm(detail.value.id); await loadWorkflow(detail.value.id); await load(); ElMessage.success(tr('PI 已确认'))
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
  try { detail.value = await piApi.payment(detail.value.id, { ...payment, amount: fromCents(amount), receivedAt: payment.receivedAt || null }); paymentVisible.value = false; detailTab.value = 'payment'; await loadWorkflow(detail.value.id); await load(); ElMessage.success(tr('到账记录已保存，流程已自动推进')) }
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
  try { detail.value = await piApi.reversePayment(detail.value.id, reversal.paymentId, reversal); reversalVisible.value = false; await load(); ElMessage.success(tr('错误到账登记已撤回，请核对当前版本并重新放行')) }
  catch (error) { reversalError.value = message(error, '撤回失败，请核对最新收款记录') }
  finally { actionBusy.value = false }
}
type ReleaseReceiptCheck = { count: number | null; error: string }
const releaseVisible = ref(false), releaseReason = ref(''), releaseError = ref('')
const releaseReceiptChecks = ref<Record<number, ReleaseReceiptCheck>>({}), releaseReceiptsBusy = ref(false)
let releaseReceiptLoadVersion = 0
const confirmedReleasePayments = computed(() => detail.value?.payments.filter(row => row.status === 'CONFIRMED') || [])
const missingReleaseReceiptCount = computed(() => manualWorkflow.value ? 0 : confirmedReleasePayments.value.filter(row => releaseReceiptChecks.value[row.id]?.count === 0).length)
const releaseReceiptCheckFailed = computed(() => confirmedReleasePayments.value.some(row => Boolean(releaseReceiptChecks.value[row.id]?.error)))
async function loadReleaseReceipts() {
  if (!detail.value || !showFinance.value) return
  const version = ++releaseReceiptLoadVersion, piId = detail.value.id, payments = confirmedReleasePayments.value
  releaseReceiptsBusy.value = true
  releaseReceiptChecks.value = Object.fromEntries(payments.map(row => [row.id, { count: null, error: '' }]))
  const results = await Promise.all(payments.map(async row => {
    try { return [row.id, { count: (await piApi.receiptAttachments(row.piId, row.id)).length, error: '' }] as const }
    catch (error) { return [row.id, { count: null, error: message(error, '凭证状态读取失败') }] as const }
  }))
  if (version !== releaseReceiptLoadVersion || !releaseVisible.value || detail.value?.id !== piId) return
  releaseReceiptChecks.value = Object.fromEntries(results)
  releaseReceiptsBusy.value = false
}
function receiptCheckLabel(payment: PiPayment) {
  const check = releaseReceiptChecks.value[payment.id]
  if (!check || check.count == null) return check?.error ? '读取失败' : '检查中'
  return check.count > 0 ? `已上传 ${check.count} 份` : '缺少凭证'
}
function receiptCheckType(payment: PiPayment) {
  const check = releaseReceiptChecks.value[payment.id]
  if (check?.error) return 'warning'
  return check?.count ? 'success' : 'danger'
}
function receiptSaved() {
  if (!receiptPayment.value || !releaseVisible.value) return
  const current = releaseReceiptChecks.value[receiptPayment.value.id]
  releaseReceiptChecks.value = { ...releaseReceiptChecks.value, [receiptPayment.value.id]: { count: (current?.count || 0) + 1, error: '' } }
}
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
    await openDetail(current); await load(); ElMessage.success(tr('库存已重新核对并占用，请核对最新占用状态'))
  } catch (error) { reservationError.value = message(error, '重新占用失败，输入内容已保留') }
  finally { actionBusy.value = false }
}
const gateMet = computed(() => { if (manualWorkflow.value) return true; if (detailProfile.value?.rules.paymentMode === 'CREDIT') return workflow.value?.steps.some(s => s.code === 'CREDIT_APPROVED' && s.status === 'COMPLETED') ?? false; return detail.value != null })
async function releaseManualPi() { releaseReason.value = '经办人确认欧洲销售交付安排，准备系统出库；实际款项另行登记'; await saveRelease() }
function openRelease() {
  releaseReason.value = ''; releaseError.value = ''; releaseReceiptChecks.value = {}; releaseVisible.value = true
  void loadReleaseReceipts()
}
watch(releaseVisible, visible => {
  if (!visible) { releaseReceiptLoadVersion++; releaseReceiptsBusy.value = false }
})
async function saveRelease() {
  if (!detail.value || !canRelease.value || actionBusy.value) return
  if (reservationBlocked.value) { releaseError.value = '来源订单占用已失效，请先处理库存占用'; return }
  if (!manualWorkflow.value && releaseReceiptsBusy.value) { releaseError.value = '正在核对收款凭证，请稍候'; return }
  if (!manualWorkflow.value && releaseReceiptCheckFailed.value) { releaseError.value = '部分收款凭证状态读取失败，请刷新后重试'; return }
  if (!manualWorkflow.value && missingReleaseReceiptCount.value > 0) { releaseError.value = `还有 ${missingReleaseReceiptCount.value} 笔已确认到账缺少收款凭证`; return }
  if (!releaseReason.value.trim()) { releaseError.value = '请填写发货放行说明'; return }
  actionBusy.value = true; releaseError.value = ''
  try { detail.value = await piApi.release(detail.value.id, releaseReason.value.trim()); releaseVisible.value = false; await loadWorkflow(detail.value.id); await load(); ElMessage.success(tr('PI 已放行发货')) }
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
    const answer = await ElMessageBox.prompt(tr('请核对承运商与实际货物后填写撤回原因。撤回历史和原运单会保留。'), tr('撤回物流'), { inputValidator: (value: string) => value.trim().length > 0 && value.length <= 500 ? true : tr('请填写1–500字原因') })
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
    logisticsVisible.value = false; await loadWorkflow(detail.value.id); await load(); ElMessage.success(tr(logistics.status === 'SHIPPED' ? '国际运输已保存，流程已自动推进' : '物流计划已保存'))
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
watch(canRead, value => { if (value) { if (props.europe) void loadEuropeWarehouses(); else void load(); void loadProfiles() } else { ++listVersion; rows.value = []; detail.value = null; detailVisible.value = false } })
watch([draft, selectedLocks, draftStep, customerBusy, customerLoadFailed], () => { persistLocalDraft() }, { deep: true, flush: 'post' })
watch(() => props.context?.username, () => {
  if (createVisible.value && localDraftOwner !== props.context?.username) { persistLocalDraft(); finishClosingDraft() }
  if (props.europe) { ++europeWarehouseLoadVersion; ++listVersion; europeWarehouseId.value = undefined; europeWarehouses.value = []; rows.value = []; total.value = 0; detail.value = null; detailVisible.value = false; void loadEuropeWarehouses() }
  refreshLocalDrafts()
})
function beforePageUnload(event: BeforeUnloadEvent) {
  if (createVisible.value && !persistLocalDraft()) { event.preventDefault(); event.returnValue = '' }
}
function saveOnPageHide() { persistLocalDraft() }
// 来自锁定转单 / 订单办理的跳转：自动打开对应新建流程，并带入客户、来源锁定或来源订单。
let appliedIntent: SalesIntent | null = null
async function applyIntent() {
  const intent = props.intent
  if (!intent || intent === appliedIntent || !canWrite.value || !canCreateInScope.value || createVisible.value || restoringDraft.value) return
  appliedIntent = intent; clearSalesIntent(intent)
  try {
    if (intent.kind === 'lock') {
      const customerId = intent.customerId
      const firstSource = intent.sources[0]
      const batch = intent.wholeBatch && firstSource ? await loadLockBatch(firstSource.lockId) : undefined
      const sources = batch ? batch.map(lock => ({ lock, quantity: lock.remainingQty, lockNo: lock.lockNo }))
        : customerId ? await Promise.all(intent.sources.map(async source => {
        const result = await piApi.lockOptions({ customerId, keyword: source.lockNo, page: 1, anyAgent: true })
        const lock = result.records.find(row => row.id === source.lockId)
        return { lock, quantity: source.quantity, lockNo: source.lockNo }
      })) : []
      if (createVisible.value || restoringDraft.value) return
      await openCreate('locks')
      if (!createVisible.value || !customerId) return
      if (!options.value.customers.some(item => item.id === customerId)) options.value.customers.push({ id: customerId, code: '', name: intent.customerName || '锁定客户' })
      lockKeyword.value = ''
      draft.customerId = customerId
      await nextTick()
      void chooseCustomer(customerId)
      const unavailable = sources.find(source => !source.lock || (source.quantity ?? 1) > source.lock.remainingQty)
      if (unavailable) {
        ElMessage.warning(tr("锁定 {value0} 的可转数量已变化，请重新选择来源和数量", { value0: unavailable.lockNo }))
        return
      }
      for (const { lock, quantity } of sources) {
        if (lock) addLock(lock, quantity)
      }
      await loadLocks()
    } else {
      await openCreate('new')
      if (!createVisible.value || !draft.document.business) return
      draft.document.business.orderSource = 'EXISTING_ORDER'
      optionKeyword.value = intent.orderNo
      await loadOptions()
      if (!options.value.customers.some(item => item.id === intent.customerId)) options.value.customers.push({ id: intent.customerId, code: '', name: intent.customerName || '订单客户' })
      draft.customerId = intent.customerId
      await nextTick(); void chooseCustomer(intent.customerId)
      if (filteredOrders.value.some(order => order.id === intent.orderId)) { draft.sourceOrderId = intent.orderId; chooseSourceOrder(intent.orderId) }
      else ElMessage.warning(tr("订单 {value0} 当前不能关联新 PI，请核对订单状态、销售类型或是否已有出库", { value0: intent.orderNo }))
    }
  } catch (error) { ElMessage.error(tr(message(error, '自动打开新建 PI 失败，请手动新建'))) }
}
watch([() => props.intent, canCreateInScope, canWrite], () => { void applyIntent() })
watch([() => props.requestedDetailId, canRead], ([id, allowed]) => {
  if (!id || !allowed) return
  void openDetail({ id })
  emit('detailRequested')
}, { immediate: true })
onMounted(() => {
  void nextTick().then(applyIntent)
  refreshLocalDrafts()
  window.addEventListener('beforeunload', beforePageUnload)
  window.addEventListener('pagehide', saveOnPageHide)
  window.addEventListener('storage', refreshLocalDrafts)
  if (canRead.value) { if (props.europe) void loadEuropeWarehouses(); else void load(); void loadProfiles(); if (internal.value && new URLSearchParams(window.location.search).get('panel') === 'profiles') profilesVisible.value = true }
})
onBeforeUnmount(() => {
  persistLocalDraft(); customerLoadVersion++; lockVersion++; profileSelectionVersion++; optionLoadVersion++; listVersion++; detailVersion++; europeWarehouseLoadVersion++
  window.removeEventListener('beforeunload', beforePageUnload)
  window.removeEventListener('pagehide', saveOnPageHide)
  window.removeEventListener('storage', refreshLocalDrafts)
})
</script>

<template>
  <el-dialog v-model="profilesVisible" :title='tr("统一履约配置")' width="min(1100px,96vw)" append-to-body destroy-on-close @closed="resetProfileDialogContext"><PiFulfillmentProfiles v-if="profilesVisible" :can-manage="canManageTemplates" :seed-profile-id="profileDialogSeedId" :seed-warehouse-id="profileDialogWarehouseId" :seed-trade-type="profileDialogTradeType" :auto-open="profileDialogAutoOpen" @changed="loadProfiles" @published="handleProfilePublished" /></el-dialog>
  <el-dialog v-model="templateVisible" :title='tr("PI模板与银行资料")' width="min(1100px, 94vw)" append-to-body destroy-on-close>
    <el-alert v-if="createVisible && draftProfile" type="info" :closable="false" :title='tr("当前单据的模板由履约配置指定。调整模板后，请在履约配置中复制新版本并选用新模板，再回到单据重新选择配置。")'>
      <el-button v-if="canManageTemplates" text type="primary" @click="templateVisible = false; openProfileRepair()">{{ tr("调整履约配置") }}</el-button>
    </el-alert>
    <PiTemplates v-if="templateVisible" :can-manage="canManageTemplates" :initial-template="templateAdjustmentSource" :trade-type="createVisible ? draft.tradeType : undefined" :selectable="createVisible && !draftProfile && !actionBusy" @select="chooseTemplate($event); templateVisible = false" />
  </el-dialog>
  <section class="pi-workspace">
    <el-empty v-if="!canRead" :description='tr("当前账号没有 PI 查看权限")' />
    <template v-else>
      <div class="pi-heading"><div><h2>{{ tr(scenario?.title) || (europe ? tr('正常 PI 销售') : isAgent ? tr('我的 PI 单据') : tr('PI 与销售履约')) }}</h2><p>{{ europe ? tr('按实际出货仓办理 PI；新单默认简化办理，客户收款与付 Totem 分别登记。') : isAgent ? tr('查看已发布的单据、商品和物流进度。') : tr('维护商品与交易条款，跟进收款、放行和物流。') }}</p></div></div>
      <el-alert v-if="genericEntry && internal" :title='tr("此处用于查询和办理已有 PI。新建 PI 请按销售类型进入：外销到「销售订单 → 外销」（260728英国builtPI 模板），欧洲到「欧洲销售与交付 → 正常 PI 销售」（France_1104_PI 模板）；内销办理购销合同。")' type="warning" :closable="false" />
      <el-alert v-if="scenario" :title="salesScenario === 'CHINA_EXPORT' ? tr('国内外销使用 PI 流程，固定采用 260728英国builtPI 模板；请核对本单收款与交付约定。') : tr('欧洲内销、外销均使用 PI 流程，固定采用 France_1104_PI 模板；出货仓、税费和银行资料按本单约定填写。')" type="info" :closable="false" />
      <section v-if="europe" class="europe-order-scope" :aria-label='tr("欧洲订单出货仓")'>
        <label for="europe-warehouse">{{ tr("捷克出货仓") }}</label>
        <el-select id="europe-warehouse" v-model="europeWarehouseId" :aria-label='tr("捷克出货仓")' filterable :loading="europeWarehousesBusy" :placeholder='tr("请选择实际的捷克出货仓")' @change="selectEuropeWarehouse">
          <el-option v-for="warehouse in europeWarehouses" :key="warehouse.id" :value="warehouse.id" :label="`${warehouse.name}（${warehouse.code}）${warehouse.status === 'ENABLED' ? '' : tr(' · 已停用')}`" />
        </el-select>
        <p>{{ tr("仅显示所选出货仓的订单；选择会保存在当前浏览器、当前账号。新建 PI 自动带入该仓库。") }}</p>
        <el-alert v-if="europeWarehouseError" :title="tr(europeWarehouseError)" type="error" :closable="false"><el-button text @click="loadEuropeWarehouses">{{ tr("重试") }}</el-button></el-alert>
        <el-alert v-if="europePreferenceError" :title="tr(europePreferenceError)" type="warning" :closable="false" />
        <el-alert v-if="europeWarehouse && !canCreateInScope" :title='tr("该仓库已停用，仅可查看历史订单；新建 PI 请选用启用仓库。")' type="warning" :closable="false" />
      </section>
      <PiBusinessDashboard v-if="internal && !europe && !salesScenario" :refresh="reportRefresh" @open-pi="id => openDetail({ id })" />
      <section v-if="hasCombinedFilters" class="pi-list-facets" :aria-label="tr('PI 列表筛选')">
        <div class="pi-filter-row" role="group" :aria-label="tr('列表范围')">
          <span class="pi-filter-label" aria-hidden="true">{{ tr('列表范围') }}</span>
          <div class="pi-filter-choices">
            <button type="button" :aria-pressed="listScope === 'daily'" @click="resetList">{{ tr('日常跟进') }}</button>
            <button type="button" :aria-pressed="listScope === 'custom'" @click="applyCustomFilters">{{ tr('自定义筛选') }}</button>
            <span v-if="listScope === 'daily'" class="pi-filter-hint">{{ tr('本月全部 PI + 往月未完成 PI') }}</span>
          </div>
        </div>
        <div class="pi-filter-row" role="group" :aria-label="tr('创建月份')">
          <span class="pi-filter-label" aria-hidden="true">{{ tr('创建月份') }}</span>
          <div class="pi-filter-choices">
            <button v-for="option in monthShortcuts" :key="option.label" type="button"
              :aria-pressed="listScope === 'custom' && !chosenMonth && monthShortcut === option.value"
              @click="selectMonth(option.value)">{{ tr(option.label) }}</button>
            <el-date-picker v-model="chosenMonth" class="pi-filter-month" type="month" size="small" format="YYYY-MM" value-format="YYYY-MM"
              :aria-label="tr('选择年月')" :placeholder="tr('选择年月')" @change="selectChosenMonth" />
          </div>
        </div>
        <div class="pi-filter-row" role="group" :aria-label="tr('PI 状态')">
          <span class="pi-filter-label" aria-hidden="true">{{ tr('PI 状态') }}</span>
          <div class="pi-filter-choices">
            <button type="button" :aria-pressed="listScope === 'custom' && !selectedPiStatuses.length"
              @click="selectStatuses('pi')">{{ tr('不限') }}</button>
            <button v-for="state in states" :key="state" type="button" :aria-pressed="selectedPiStatuses.includes(state)"
              @click="selectStatuses('pi', state)">{{ tr(stateNames[state]) }}</button>
          </div>
        </div>
        <div v-if="showFinance" class="pi-filter-row" role="group" :aria-label="tr('收款状态')">
          <span class="pi-filter-label" aria-hidden="true">{{ tr('收款状态') }}</span>
          <div class="pi-filter-choices">
            <button type="button" :aria-pressed="listScope === 'custom' && !selectedPaymentStatuses.length"
              @click="selectStatuses('payment')">{{ tr('不限') }}</button>
            <button v-for="(label, state) in paymentNames" :key="state" type="button"
              :aria-pressed="selectedPaymentStatuses.includes(state)" @click="selectStatuses('payment', state)">{{ tr(label) }}</button>
          </div>
        </div>
        <div class="pi-filter-row" role="group" :aria-label="tr('履约进度')">
          <span class="pi-filter-label" aria-hidden="true">{{ tr('履约进度') }}</span>
          <div class="pi-filter-choices">
            <button v-for="option in fulfillmentOptions" :key="option.label" type="button"
              :aria-pressed="listScope === 'custom' && fulfillmentCompleted === option.value"
              @click="selectFulfillment(option.value)">{{ tr(option.label) }}</button>
          </div>
        </div>
      </section>
      <form class="pi-filters list-toolbar" @submit.prevent="search">
        <el-input v-model="keyword" :aria-label="tr('搜索PI、客户、SKU、订单、出库单或物流单号')"
          :placeholder="tr('PI / 客户 / SKU / 订单 / 出库单 / 物流单号')" clearable />
        <el-select v-if="!hasCombinedFilters" v-model="status" :aria-label="tr('PI 状态')" clearable :placeholder="tr('全部状态')">
          <el-option v-for="state in states" :key="state" :value="state" :label="tr(stateNames[state])" />
        </el-select>
        <el-button native-type="submit" type="primary" :loading="busy" :disabled="!europeScopeReady">{{ tr('查询') }}</el-button>
        <el-button @click="resetList">{{ tr('重置') }}</el-button>
        <el-button v-if="canExport" :loading="exportBusy" :disabled="!europeScopeReady || (hasCombinedFilters && busy)"
          @click="exportList">{{ tr('导出当前查询全部 PI') }}</el-button>
        <el-button v-if="internal" @click="openProfiles">{{ tr('履约配置') }}</el-button>
        <el-button v-if="canManageTemplates" @click="openTemplates()">{{ tr('模板与银行资料') }}</el-button>
        <el-button v-if="canWrite && canManageReservation && allowCreate !== false" :disabled="!canCreateInScope"
          @click="openConversion">{{ tr('从 SKU 锁定生成 PI') }}</el-button>
        <el-button v-if="canWrite && allowCreate !== false" type="primary" :disabled="!canCreateInScope"
          @click="openCreate()">{{ tr('新建 PI') }}</el-button>
      </form>
<el-alert v-if="internal && profilesError" :title="tr(profilesError)" type="error" :closable="false"><el-button text @click="loadProfiles">{{ tr("重试加载履约配置") }}</el-button></el-alert>
      <el-alert v-if="canWrite && localDraftError" :title="tr(localDraftError)" type="error" :closable="false"><el-button text @click="refreshLocalDrafts">{{ tr("重新读取") }}</el-button></el-alert>
      <section v-if="canWrite && localDrafts.length" class="pi-local-drafts" :aria-label='tr("本机草稿")'>
        <div class="pi-local-drafts-heading"><strong>{{ trInline("待继续填写的本机草稿（") }}{{ localDrafts.length }}）</strong><span>{{ tr("仅当前浏览器、当前账号可恢复；清理浏览器数据会删除草稿。") }}</span></div>
        <article v-for="saved in localDrafts" :key="saved.id">
          <div><strong>{{ saved.content.options.customers[0]?.name || tr('未选择客户') }}</strong><p>{{ saved.content.editingId ? tr('编辑 PI #') + saved.content.editingId : saved.content.fromLocks ? tr('从 SKU 锁定生成 PI') : tr('新建 PI') }} {{ trInline("· 第") }} {{ saved.content.step + 1 }} {{ trInline("步 ·") }} {{ new Date(saved.savedAt).toLocaleString() }}</p></div>
          <div class="pi-local-draft-actions"><el-button type="primary" plain :disabled="restoringDraft || createVisible" @click="restoreLocalDraft(saved)">{{ tr("继续填写") }}</el-button><el-button text type="danger" :disabled="restoringDraft || createVisible" @click="removeLocalDraft(saved)">{{ tr("删除") }}</el-button></div>
        </article>
      </section>
      <el-alert v-if="loadError" :title="tr(loadError)" type="error" show-icon :closable="false" />
      <el-table v-loading="busy" :data="rows" :empty-text="europe && !europeScopeReady ? tr('请先选择捷克出货仓') : tr('没有符合条件的 PI')" row-key="id" max-height="calc(100vh - 220px)" scrollbar-always-on>
      <RecordIdColumn />
        <el-table-column prop="piNo" :label='tr("PI 编号")' min-width="200"><template #default="scope"><el-button link type="primary" @click="openDetail(scope.row)">{{ scope.row.piNo }}</el-button></template></el-table-column>
        <el-table-column prop="revision" :label='tr("版本")' width="70" /><el-table-column prop="customerName" :label='tr("客户")' min-width="150" />
        <el-table-column :label='tr("贸易")' width="80"><template #default="scope">{{ scope.row.tradeType === 'EXPORT' ? tr('外销') : tr('内销') }}</template></el-table-column>
        <el-table-column :label='tr("总金额")' min-width="145"><template #default="scope">{{ scope.row.currencyCode }} {{ money(scope.row.totalAmount) }}</template></el-table-column>
        <el-table-column :label='tr("状态")' width="100"><template #default="scope"><el-tag :type="scope.row.status === 'RELEASED' ? 'success' : scope.row.status === 'DRAFT' ? 'info' : 'primary'">{{ tr(stateNames[scope.row.status] ?? scope.row.status) }}</el-tag></template></el-table-column>
        <el-table-column v-if="showFinance" :label='tr("收款状态")' min-width="125"><template #default="scope">{{ tr(paymentNames[scope.row.paymentStatus] ?? scope.row.paymentStatus ?? '—') }}</template></el-table-column>
        <el-table-column :label='tr("库存占用")' min-width="185"><template #default="scope">{{ tr(reservationLabel(scope.row.reservationStatus, isAgent)) }}</template></el-table-column>
        <el-table-column :label='tr("发货进度")' min-width="140"><template #default="{ row }">{{ shipmentProgress(row) }}</template></el-table-column>
        <el-table-column prop="businessOwner" :label='tr("负责人")' min-width="120" />
        <el-table-column :label='tr("创建时间")' min-width="180"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
        <el-table-column :label='tr("修改时间")' min-width="180"><template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template></el-table-column>
        <el-table-column prop="plannedShipDate" :label='tr("计划发货")' width="120" /><el-table-column :label='tr("操作")' width="90" fixed="right"><template #default="scope"><el-button link type="primary" @click="openDetail(scope.row)">{{ tr("查看详情") }}</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[10, 20, 50, 100]" :total="total" layout="total, sizes, prev, pager, next" @current-change="load" @size-change="search" />
    </template>

    <el-dialog v-model="leaveDraftVisible" :title='tr("离开开单？")' width="min(520px, 94vw)" append-to-body :close-on-click-modal="false" class="pi-leave-draft-dialog">
      <p>{{ tr("可以先保存草稿，下次从 PI 页面的“本机草稿”继续填写，未填完整也能保存。") }}</p>
      <el-alert v-if="localSaveError" :title="tr(localSaveError)" type="error" :closable="false" />
      <template #footer><div class="pi-leave-draft-actions"><el-button @click="leaveDraftVisible = false">{{ tr("继续填写") }}</el-button><el-button type="danger" plain @click="discardDraftAndLeave">{{ tr("放弃并离开") }}</el-button><el-button type="primary" @click="saveDraftAndLeave">{{ tr("保存草稿并离开") }}</el-button></div></template>
    </el-dialog>
    <el-dialog v-model="createVisible" :title="tr(draftTitle)" class="pi-draft-dialog" width="min(1280px, calc(100vw - 40px))" top="4vh" :close-on-click-modal="false" :close-on-press-escape="!actionBusy" :show-close="!actionBusy" :before-close="closeDraft" destroy-on-close @opened="focusDraftIssue">
      <template #header><div class="pi-draft-heading"><span class="pi-document-mark" aria-hidden="true">PI</span><div><span class="pi-eyebrow">PROFORMA INVOICE</span><h2>{{ tr(draftTitle) }}</h2><p>{{ tr("先把订单开好，后续收款与履约在详情中继续。") }}</p></div><span class="pi-draft-badge">{{ tr("草稿 · 未发布") }}</span></div></template>
      <nav class="pi-draft-steps" :aria-label='tr("PI 开单步骤")'>
        <button v-for="(step, index) in draftSteps" :key="step.title" type="button" :class="{ active: draftStep === index, passed: draftStep > index }" :aria-current="draftStep === index ? 'step' : undefined" :disabled="actionBusy" @click="goToDraftStep(index)"><span class="pi-step-number">{{ draftStep > index ? '✓' : '0' + (index + 1) }}</span><span><strong>{{ tr(step.title) }}</strong><small>{{ tr(step.description) }}</small></span><span v-if="index < 2" class="pi-step-arrow" aria-hidden="true">›</span></button>
      </nav>
      <div class="pi-draft-layout">
        <main ref="draftScroll" class="pi-draft-scroll" :aria-label="tr(draftSteps[draftStep]?.title)">
          <el-form label-position="top" class="pi-draft-form" :disabled="actionBusy || optionsBusy || restoringDraft" @submit.prevent="draftStep < 2 ? goToDraftStep(draftStep + 1) : saveCreate()">
            <section v-show="draftStep === 0" class="pi-draft-step">
              <div class="pi-step-heading"><span class="pi-eyebrow">STEP 01</span><h2>{{ tr("这份 PI，开给谁？") }}</h2><p>{{ tr("选择客户后带入商务资料，再添加本次销售商品。") }}</p></div>
              <section class="pi-form-card" data-draft-field="options">
                <div class="pi-section-heading"><h3>{{ tr("客户信息") }}</h3><span class="pi-muted">{{ tr("带 * 的为必填项") }}</span></div>
                <div class="pi-draft-grid">
                  <el-form-item :label='tr("订单客户")' required data-draft-field="customer" :error="fieldError('customer')"><el-select v-model="draft.customerId" :aria-label='tr("订单客户")' :loading="customerBusy || optionsBusy" @change="chooseCustomer" :disabled="editingRevision || fixedItems && !fromLocks" filterable :placeholder='tr("搜索或选择客户")'><el-option v-for="customer in options.customers" :key="customer.id" :value="customer.id" :label="customer.code + ' · ' + customer.name" /></el-select></el-form-item>
                  <div v-if="draft.document.business" class="pi-draft-grid" data-draft-field="warehouse">
                  <el-form-item :label='tr("出库仓库（不同仓库分别开 PI）")' required :error="fieldError('warehouse')"><el-select v-model="draft.document.business.warehouseId" :disabled="europe || editingRevision && Boolean(detail?.document?.business)" filterable :placeholder='tr("先选择实际出库仓库")' @change="changeWarehouse"><el-option v-for="warehouse in options.warehouses || []" :key="warehouse.id" :value="warehouse.id" :label="warehouse.name" /></el-select></el-form-item>
                  <el-form-item :label='tr("履约配置")' :required="requiresProfile" data-draft-field="fulfillmentProfile" :error="fieldError('fulfillmentProfile')"><el-select :model-value="draft.document.business.fulfillmentProfileId" :clearable="!requiresProfile" filterable :loading="profilesLoading" :disabled="!selectedWarehouseId || editingRevision || optionsBusy || profilesLoading" :placeholder="!selectedWarehouseId ? tr('请先选择出库仓库') : requiresProfile ? tr('选择适用于当前仓库和贸易类型的配置') : tr('历史单据可沿用原规则')" @change="chooseProfile"><el-option v-for="p in selectableProfiles" :key="p.id" :value="p.id" :label="`${p.name} · ${p.version}`" /></el-select></el-form-item>
                  <el-alert v-if="profilesError" :title="tr(profilesError)" type="error" :closable="false"><el-button text @click="loadProfiles">{{ tr("重试") }}</el-button></el-alert>
                  <el-alert v-else-if="showProfileGap" class="pi-span-two" :title="tr('{value0}尚未配置{value1}收款与发货规则', { value0: selectedWarehouse?.name || '当前仓库', value1: draft.tradeType === 'EXPORT' ? '外销' : '内销' })" type="warning" :closable="false">
                    <p>{{ tr("需由管理员先完成一次配置，后续同类 PI 可重复选用。") }}</p>
                    <p>{{ trInline("① 选择") }}{{ draft.tradeType === 'EXPORT' ? tr('外销') : tr('内销') }} {{ trInline("PI 模板并核对收款资料；② 选择当前仓库，核对付款、运输及单据要求；③ 发布后自动带回本草稿。") }}</p>
                    <p v-if="profileRepairSource">{{ trInline("已有") }}{{ draft.tradeType === 'EXPORT' ? tr('外销') : tr('内销') }}{{ trInline("配置可供复制，系统会带入当前仓库，请核对其他规则是否适用。") }}</p>
                    <el-button v-if="canManageTemplates" type="primary" plain @click="openProfileRepair">{{ draft.tradeType === 'EXPORT' ? tr('配置外销收款与发货规则') : tr('配置内销收款与发货规则') }}</el-button>
                    <span v-else>{{ tr("请联系有“PI 模板管理”权限的管理员，按以上步骤发布配置后，再刷新选用。") }}</span>
                  </el-alert>
                  <p v-if="draftProfile" class="pi-field-help">{{ draftProfile.rules.paymentMode === 'CREDIT' ? tr('客户收到货后付款：须逐单授权后发货，实际到账另行登记；不固定付款日期，具体约定以 PI 付款条款为准。') : tr('发出 PI 后付款：按约定收款金额保留库存与放行，具体付款时间以 PI 付款条款为准。') }}{{ trInline("交付：") }}{{ draftProfile.rules.deliveryMode === 'CUSTOMER_PICKUP' ? tr('客户货代提货') : tr('我方安排运输') }}{{ trInline("。所需单据：") }}{{ draftProfile.rules.requiredDocuments.join('、') || tr('按配置办理') }}。</p>
                  <el-alert v-if="draft.document.business.workflowMode === 'USER_CONFIRMED'" class="pi-span-two" :title='tr("本单采用简化办理：正常填写后确认推进，合同、附件和款项资料可后补；实际到账及系统出库另行登记。")' type="info" :closable="false" />
                  <el-form-item :label='tr("是否从国内出货")' required><el-select v-model="draft.document.business.domesticShipment" :disabled="europe || editingRevision && Boolean(detail?.document?.business)"><el-option :value="true" :label="salesScenario ? tr('是，从国内仓库出货') : draftProfile ? tr('是（合同要求以履约配置为准）') : tr('是，须办理 SAVA／银三环购销合同')" /><el-option :value="false" :label="draftProfile ? tr('否（合同要求以履约配置为准）') : tr('否，不适用国内购销合同')" /></el-select></el-form-item>
                </div><div class="pi-draft-grid pi-trade-fields"><el-form-item :label='tr("贸易类型")' required><el-select v-model="draft.tradeType" :aria-label='tr("贸易类型")' :disabled="Boolean(salesScenario) || editingRevision" @change="changeTradeType"><el-option value="DOMESTIC" :label='tr("内销")' /><el-option value="EXPORT" :label='tr("外销")' /></el-select></el-form-item><el-form-item :label='tr("币种")' required data-draft-field="currency" :error="fieldError('currency')"><el-input v-model="draft.currencyCode" :aria-label='tr("币种")' :disabled="editingRevision" maxlength="3" placeholder="CNY / USD / EUR" /></el-form-item></div>
                </div>
                <div v-if="customerBusy" class="pi-field-help" role="status">{{ tr("正在带入客户商务资料…") }}</div>
                <el-alert v-if="customerLoadFailed" :title='tr("客户资料带入失败，当前内容暂不能提交")' type="error" :closable="false"><el-button text :disabled="actionBusy" @click="draft.customerId && chooseCustomer(draft.customerId)">{{ tr("重试加载客户资料") }}</el-button></el-alert>
                <section v-if="draft.customerId" class="pi-disclosure">
                  <div class="pi-section-heading"><div><h3>{{ tr("客户商务资料") }}</h3><p>{{ tr("请核对并补齐必填资料，带 * 的为必填项。") }}</p></div></div>
                  <div class="pi-draft-grid"><el-form-item v-for="[key, label, maxLength] in buyerFields" :key="key" :label="tr(label)" :required="isBuyerRequired(key)" :data-draft-field="key" :error="fieldError(key)" :class="{ 'pi-span-two': key === 'buyerAddress' }"><el-input v-model="draft.document[key]" :aria-label="tr(label)" :aria-required="isBuyerRequired(key)" :disabled="customerBusy" :type="key === 'buyerAddress' ? 'textarea' : 'text'" :rows="2" :maxlength="maxLength" /></el-form-item></div>
                </section>
                <div class="pi-disclosure">
                  <h3>{{ tr("订单来源与所属客户") }}</h3>
                  <el-form-item v-if="!fromLocks && !hasBoundOrders && draft.document.business" :label='tr("订单来源")' required data-draft-field="orderSource" :error="fieldError('orderSource')">
                    <el-radio-group v-model="draft.document.business.orderSource" :aria-label='tr("订单来源")' @change="changeOrderSource"><el-radio value="EXISTING_ORDER">{{ tr("关联已有订单") }}</el-radio><el-radio value="NEW_BUSINESS">{{ tr("无原订单的新业务") }}</el-radio></el-radio-group>
                  </el-form-item>
                  <el-alert v-if="fromLocks" :title='tr(selectedLocks.some(lock => lock.lockRequestId) ? "商品数量沿用本批各 SKU 的预锁数量。" : "商品数量按所选锁定的本次转出数量汇总，可在来源锁定表中调整。")' type="info" :closable="false" />
                  <el-alert v-else-if="!hasBoundOrders && draft.document.business?.orderSource === 'NEW_BUSINESS'" :title='tr("仅用于没有原订单的新业务。发出 PI 后付款达到收款门槛，或客户收到货后付款获批后，按所选仓库保留库存并生成关联订单；已有锁单或订单请使用对应入口。")' type="warning" :closable="false" />
                  <div class="pi-draft-grid">
                    <el-form-item v-if="!fromLocks && (draft.sourceOrderId || !hasBoundOrders && draft.document.business?.orderSource === 'EXISTING_ORDER')" :label='tr("来源销售订单")' required data-draft-field="sourceOrder" :error="fieldError('sourceOrder')"><el-select v-model="draft.sourceOrderId" :aria-label='tr("来源销售订单")' clearable filterable :disabled="!draft.customerId || hasBoundOrders || editingRevision" :placeholder='tr("先选择客户，再选择原订单")' @change="chooseSourceOrder"><el-option v-for="order in filteredOrders" :key="order.id" :value="order.id" :label="order.orderNo" /></el-select><p class="pi-field-help">{{ tr("自动带入原订单的所属客户、SKU 和数量，复用原库存占用。") }}<template v-if="scenarioSalesType">{{ trInline("仅显示已确定为「") }}{{ tr(salesTypeLabel(scenarioSalesType)) }}{{ trInline("」的订单") }}<template v-if="hiddenTypeOrders">{{ trInline("，该客户另有") }} {{ hiddenTypeOrders }} {{ trInline("笔其他类型或待选类型订单未显示（请先在订单办理中选择类型）") }}</template>。</template></p></el-form-item>
                    <el-form-item :label='tr("所属客户")'><el-select v-model="draft.agentId" :aria-label='tr("所属客户")' :disabled="editingRevision || fixedItems && !fromLocks" clearable filterable :placeholder='tr("选择所属客户，关联订单后自动带入")'><el-option v-for="agent in options.agents" :key="agent.id" :value="agent.id" :label="agent.code + ' · ' + agent.name" /></el-select></el-form-item>
                  </div>
                </div>
              </section>
              <section class="pi-form-card" data-draft-field="items">
                <div class="pi-section-heading"><div><h3>{{ tr("商品明细") }} <span class="pi-count-badge">{{ draft.items.length }}</span></h3><p>{{ fromLocks ? tr('从下方锁定中选择商品，再填写单价。') : tr('选择 SKU 后填写数量与单价，图片在保存时带入。') }}</p></div><el-button :disabled="fixedItems || draft.items.length >= 100" @click="draft.items.push(newItem())">{{ tr("＋ 添加商品") }}</el-button></div>
                <el-alert v-if="editingRevision && fixedItems" :title='tr("数量和移除商品在确认修订时生效，不能少于已发数量；新增 SKU 须选择来源锁定。")' type="info" :closable="false" />
      <section data-draft-field="locks" class="pi-lock-selection" v-if="fromLocks || revisionFulfillment && canManageReservation">
        <p v-if="fromLocks" class="pi-hint">{{ tr("选择同一次预锁中的任意 SKU 即可带入整批，保存后生成一个订单号和一份 PI 草稿。") }}</p>
        <p v-if="revisionFulfillment" class="pi-hint">{{ tr("修订新增 SKU 时选择同客户的预锁批次，并将这些数量计入下方商品明细。确认修订后转入履约订单。") }}</p>
        <div class="pi-filters"><el-input v-model="lockKeyword" :placeholder='tr("按锁定单号或 SKU 搜索")' /><el-button :disabled="!draft.customerId" :loading="lockBusy" @click="lockPage = 1; loadLocks()">{{ tr("查找来源锁定") }}</el-button></div>
        <el-alert v-if="lockError" :title="tr(lockError)" type="error" :closable="false" />
        <el-table :data="lockRows" v-loading="lockBusy">
          <template #empty>
            <div v-if="!draft.customerId" class="pi-lock-empty">{{ tr("选择客户后查询可转换的SKU锁定") }}</div>
            <div v-else class="pi-lock-empty">
              <strong>{{ tr("该客户没有可转换的 SKU 锁定") }}</strong>
              <ul v-if="lockEmptyReasons.length"><li v-for="reason in lockEmptyReasons" :key="reason">{{ reason }}</li></ul>
              <p v-else>{{ tr("只显示未到期、仍有剩余可转数量的 SKU 锁定。") }}</p>
              <p v-if="fromLocks && !revisionFulfillment">{{ tr("不需要锁定的话，可以直接新建 PI，自己选 SKU、填数量和单价。") }}</p>
              <el-button v-if="fromLocks && !revisionFulfillment" type="primary" plain @click="switchToNewPi">{{ tr("改用新建 PI（直接选 SKU）") }}</el-button>
            </div>
          </template>
      <RecordIdColumn />
          <el-table-column prop="lockNo" :label='tr("来源锁定")' min-width="180" /><el-table-column prop="skuCode" label="SKU" /><el-table-column :label='tr("客户")' min-width="120"><template #default="{ row }">{{ row.agentName || tr('未关联客户') }}</template></el-table-column><el-table-column prop="remainingQty" :label='tr("剩余可转")' width="100" /><el-table-column prop="expireTime" :label='tr("原截止")' min-width="170" />
          <el-table-column :label='tr("选择")' width="110"><template #default="{ row }"><el-button link :disabled="lockBusy || selectedLocks.some(item => item.id === row.id)" @click="selectLock(row)">{{ tr(row.lockRequestId ? "加入整批" : "加入") }}</el-button></template></el-table-column>
        </el-table>
        <el-pagination v-model:current-page="lockPage" :total="lockTotal" :page-size="20" layout="total, prev, pager, next" @current-change="loadLocks" />
        <el-table v-if="selectedLocks.length" :data="selectedLocks">
          <RecordIdColumn />
          <el-table-column prop="lockNo" :label='tr("已选来源")' min-width="180" />
          <el-table-column prop="skuCode" label="SKU" min-width="120" />
          <el-table-column prop="remainingQty" :label='tr("剩余可转")' width="100" />
          <el-table-column :label='tr("本次转出")' width="180">
            <template #default="{ row }">
              <span v-if="row.lockRequestId || row.remainingQty === 1">{{ row.quantity }}</span>
              <el-input-number v-else v-model="row.quantity" :aria-label="row.lockNo + ' ' + tr('本次转出')"
                :min="1" :max="row.remainingQty" :precision="0" @change="syncLockItems" />
            </template>
          </el-table-column>
          <el-table-column :label='tr("操作")' width="80">
            <template #default="{ row }"><el-button link type="danger" @click="removeLock(row.id)">{{ tr(row.lockRequestId ? "移除整批" : "移除") }}</el-button></template>
          </el-table-column>
        </el-table>
      </section>
                <div v-if="!draft.items.length" class="pi-inline-empty">{{ tr("还没有商品，请先选择来源锁定。") }}</div>
                <article v-for="(item, index) in draft.items" :key="item.key" class="pi-item-card">
                  <div class="pi-item-heading"><span>{{ trInline("商品") }} {{ String(index + 1).padStart(2, '0') }}</span><el-button text type="danger" :disabled="fixedItems && !editingRevision || draft.items.length === 1" :aria-label="tr('移除第') + (index + 1) + tr('行商品')" @click="removeItem(index)">{{ tr("移除") }}</el-button></div>
                  <el-form-item :label='tr("SKU / 商品 / 规格")' required :data-draft-field="'sku-' + item.key" :error="fieldError('sku-' + item.key)"><el-select v-model="item.skuId" @change="item.imageAttachmentId = undefined" :disabled="fixedItems" filterable :aria-label="tr('第') + (index + 1) + tr('行 SKU')" :placeholder='tr("搜索或选择 SKU")'><el-option v-for="sku in options.skus" :key="sku.id" :value="sku.id" :disabled="sku.enabled === false || draft.items.some(other => other.key !== item.key && other.skuId === sku.id)" :label="sku.skuCode + ' · ' + sku.productName + (sku.specification ? ' · ' + sku.specification : '')" /></el-select></el-form-item>
                  <div class="pi-item-values">
                    <el-form-item :label='tr("数量")' required :data-draft-field="'quantity-' + item.key" :error="fieldError('quantity-' + item.key)"><el-input-number v-model="item.quantity" :disabled="fixedItems && !editingRevision" :aria-label="tr('第') + (index + 1) + tr('行数量')" :min="1" :max="2147483647" :precision="0" controls-position="right" /></el-form-item>
                    <el-form-item :label='tr("单位")' :data-draft-field="'unit-' + item.key" :error="fieldError('unit-' + item.key)"><el-input v-model="item.unit" maxlength="32" :aria-label="tr('第') + (index + 1) + tr('行单位')" /></el-form-item>
                    <el-form-item :label="tr('单价 / ') + draft.currencyCode.toUpperCase()" required :data-draft-field="'price-' + item.key" :error="fieldError('price-' + item.key)"><el-input v-model="item.unitPrice" :aria-label="tr('第') + (index + 1) + tr('行单价')" inputmode="decimal" :placeholder='tr("填写实际单价")' /></el-form-item>
                    <div class="pi-item-total"><span>{{ tr("行金额") }}</span><strong>{{ lineTotal(item) == null ? tr('待填写') : fromCents(lineTotal(item)!) }}</strong></div>
                  </div>
                  <details class="pi-line-adjustment" :data-draft-field="'adjustment-' + item.key" :open="cents(item.discountAmount) !== 0n || cents(item.taxAmount) !== 0n"><summary>{{ tr("行折扣与税费") }} <small>{{ cents(item.discountAmount) === 0n && cents(item.taxAmount) === 0n ? tr('默认无调整，按需展开') : tr('已填写调整金额') }}</small></summary><div class="pi-draft-grid"><el-form-item :label='tr("行折扣")'><el-input v-model="item.discountAmount" :aria-label="tr('第') + (index + 1) + tr('行折扣')" inputmode="decimal" /></el-form-item><el-form-item :label='tr("行税费")'><el-input v-model="item.taxAmount" :aria-label="tr('第') + (index + 1) + tr('行税费')" inputmode="decimal" /></el-form-item></div></details>
                </article>
                <div class="pi-catalog-search"><el-input v-model="optionKeyword" :aria-label='tr("搜索 PI 基础资料")' :placeholder='tr("找不到资料？输入客户、SKU 或订单编号")' clearable @keydown.enter.prevent="loadOptions" /><el-button :loading="optionsBusy" @click="loadOptions">{{ tr("查找资料") }}</el-button></div><p class="pi-field-help">{{ tr("每类最多显示 200 条；搜索会保留已选择的项目。") }}</p>
                <el-alert v-if="optionsError" :title="tr(optionsError)" type="error" :closable="false"><el-button text @click="loadOptions">{{ tr("重试加载") }}</el-button></el-alert>
              </section>
            </section>
            <section v-show="draftStep === 1" class="pi-draft-step">
              <div class="pi-step-heading"><span class="pi-eyebrow">STEP 02</span><h2>{{ tr("把交易约定说清楚。") }}</h2><p>{{ tr("模板负责固定资料，你只需填写这笔订单的实际约定。") }}</p></div>
              <section class="pi-form-card" data-draft-field="template"><PiTemplatePicker :fixed-layout-name="fixedLayoutName" :trade-type="draft.tradeType" :document="draft.document" :can-manage="canManageTemplates" :disabled="actionBusy || Boolean(draftProfile)" :profile-bound="Boolean(draftProfile)" :manage-disabled="actionBusy" :refresh-key="templateRefreshKey" @select="chooseTemplate" @manage="openTemplates()" @adjust="openTemplates" /></section>
              <section class="pi-form-card" data-draft-field="bank"><div class="pi-section-heading"><h3>{{ trInline("收款信息 ·") }} {{ draft.document.business?.collectionEntity || tr('待选择') }}</h3></div><p class="pi-field-help">{{ tr("模板带入账户后可按本单更新；保存为本单快照，已确认单据须通过修订调整。") }}</p><div class="pi-draft-grid"><el-form-item :label='tr("收款银行")' required><el-input v-model="draft.document.bankName" maxlength="200" /></el-form-item><el-form-item :label='tr("收款账户")' required><el-input v-model="draft.document.bankAccount" maxlength="200" /></el-form-item><el-form-item :label='tr("收款人 / 公司全称")' required><el-input v-model="draft.document.beneficiary" maxlength="200" /></el-form-item><el-form-item :label='tr("银行地址 Bank Address（选填）")' class="pi-span-two"><el-input v-model="draft.document.bankAddress" maxlength="500" /></el-form-item><el-form-item :label='tr("SWIFT Code（选填）")'><el-input v-model="draft.document.swiftCode" maxlength="64" /></el-form-item><el-form-item :label='tr("Bank Code（选填）")'><el-input v-model="draft.document.bankCode" maxlength="64" /></el-form-item><el-form-item :label='tr("Branch Code（选填）")'><el-input v-model="draft.document.branchCode" maxlength="64" /></el-form-item></div><p class="pi-field-help">{{ tr("银行附加资料及卖方登记号 / VAT 随模板带入；单据按原件位置显示，未填写的栏目留空。") }}</p></section>
              <section class="pi-form-card"><div class="pi-section-heading"><div><h3>{{ tr("付款约定") }}</h3><p>{{ tr("收款门槛不会自动填写比例；请按实际约定确认。") }}</p></div></div>
                <el-form-item :label='tr("付款条款")' required data-draft-field="paymentTerms" :error="fieldError('paymentTerms')"><el-input v-model="draft.document.paymentTerms" :aria-label='tr("付款条款")' type="textarea" :rows="3" maxlength="2000" :placeholder='tr("填写双方约定的付款方式、时间及条件")' /></el-form-item>
                <div class="pi-draft-grid"><el-form-item :label='tr("定金约定金额")' required data-draft-field="deposit" :error="fieldError('deposit')"><el-input v-model="draft.depositRequiredAmount" :aria-label='tr("定金约定金额")' inputmode="decimal" :placeholder='tr("无门槛须明确填写 0.00")'><template #append>{{ draft.currencyCode.toUpperCase() }}</template></el-input><span class="pi-field-help">{{ tr("发出 PI 后付款按本金额保留库存；客户收到货后付款按授权保留，到账另行登记。") }}</span></el-form-item><el-form-item :label='tr("发货放行门槛")' data-draft-field="shipment" :error="fieldError('shipment')"><el-input v-model="draft.shipmentRequiredAmount" :aria-label='tr("发货放行门槛")' inputmode="decimal" :placeholder='tr("选填")'><template #append>{{ draft.currencyCode.toUpperCase() }}</template></el-input><span class="pi-field-help">{{ tr("选填，仅作记录，不限制发货放行。") }}</span></el-form-item></div>
              </section>
              <section class="pi-form-card"><div class="pi-section-heading"><h3>{{ tr("交付安排") }}</h3><span class="pi-muted">{{ tr("带 * 的为必填项") }}</span></div>
                <el-form-item :label='tr("交付条款")' required data-draft-field="deliveryTerms" :error="fieldError('deliveryTerms')"><el-input v-model="draft.document.deliveryTerms" :aria-label='tr("交付条款")' type="textarea" :rows="3" maxlength="2000" :placeholder='tr("填写交货地点、交付责任及其他约定")' /></el-form-item>
                <div class="pi-draft-grid"><el-form-item :label='tr("计划发货日期")'><el-date-picker v-model="draft.plannedShipDate" :aria-label='tr("计划发货日期")' type="date" value-format="YYYY-MM-DD" :placeholder='tr("选择日期")' /></el-form-item><el-form-item :label='tr("运输方式")' required data-draft-field="transportMode" :error="fieldError('transportMode')"><el-input v-model="draft.document.transportMode" :aria-label='tr("运输方式")' maxlength="100" :placeholder='tr("填写实际运输方式，例如卡车运输或客户自提")' /></el-form-item><el-form-item :label='tr("开单日期")' required data-draft-field="issueDate" :error="fieldError('issueDate')"><el-date-picker v-model="draft.document.issueDate" :aria-label='tr("开单日期")' type="date" value-format="YYYY-MM-DD" /></el-form-item><el-form-item :label='tr("有效期至")' required data-draft-field="validUntil" :error="fieldError('validUntil')"><el-date-picker v-model="draft.document.validUntil" :aria-label='tr("有效期至")' type="date" value-format="YYYY-MM-DD" /></el-form-item><el-form-item v-if="draft.tradeType === 'EXPORT' || draft.document.tradeTerms" :label='tr("贸易术语（选填）")' data-draft-field="tradeTerms" :error="fieldError('tradeTerms')" class="pi-span-two"><el-input v-model="draft.document.tradeTerms" :aria-label='tr("贸易术语")' maxlength="100" :placeholder='tr("非欧盟或涉及报关时按合同填写，例如 DDP、DAP")' /><span class="pi-field-help">{{ tr("欧盟地区采用卡车运输或客户自提且无贸易术语约定时可留空。") }}</span></el-form-item><el-form-item :label='tr("收货地址")' required data-draft-field="shippingAddress" :error="fieldError('shippingAddress')" class="pi-span-two"><el-input v-model="draft.document.shippingAddress" :aria-label='tr("收货地址")' type="textarea" :rows="2" maxlength="1000" :placeholder='tr("从客户资料带入，可针对本单修改")' /></el-form-item><el-form-item v-if="draft.document.business?.salesChannel === 'CZECH_PI' || draft.document.dispatchAddress" :label='tr("发货地址 Dispatch Address（选填）")' class="pi-span-two"><el-input v-model="draft.document.dispatchAddress" :aria-label='tr("发货地址")' type="textarea" :rows="2" maxlength="1000" :placeholder='tr("实际发货仓库名称及地址，显示在 France_1104_PI 的 Dispatch Address")' /></el-form-item><el-form-item v-if="draft.document.business?.salesChannel === 'CHINA_SALES' || draft.document.productCategory" :label='tr("单据品类标题（选填）")' class="pi-span-two"><el-input v-model="draft.document.productCategory" :aria-label='tr("单据品类标题")' maxlength="120" :placeholder='tr("显示在 INVOICE 下方，例如 EBIKE and FRAME")' /></el-form-item></div>
              </section>
              <section class="pi-form-card"><details class="pi-disclosure pi-fees-disclosure" data-draft-field="fees" :open="feesExpanded" @toggle="feesExpanded = ($event.target as HTMLDetailsElement).open"><summary><span><strong>{{ tr("整单折扣与费用") }}</strong><small>{{ tr("有运费、税费或额外折扣时填写") }}</small></span><span>{{ tr("按需展开") }}</span></summary><div class="pi-draft-grid"><el-form-item :label='tr("全单追加折扣")'><PiFeeInput v-model="draft.discountAmount" :aria-label='tr("全单追加折扣")' /></el-form-item><el-form-item :label='tr("全单追加税费")'><PiFeeInput v-model="draft.taxAmount" :aria-label='tr("全单追加税费")' /></el-form-item><el-form-item :label='tr("运费")'><PiFeeInput v-model="draft.freightAmount" :aria-label='tr("运费")' /></el-form-item><el-form-item :label='tr("其他费用")'><PiFeeInput v-model="draft.otherFeeAmount" :aria-label='tr("其他费用")' /></el-form-item></div></details><el-form-item :label='tr("备注（选填）")'><el-input v-model="draft.remark" :aria-label='tr("PI 备注")' type="textarea" :rows="2" maxlength="1000" show-word-limit /></el-form-item></section>
            </section>
            <section v-if="draftStep === 2" class="pi-draft-step">
              <div class="pi-step-heading"><span class="pi-eyebrow">STEP 03</span><h2>{{ tr("最后，核对一下。") }}</h2><p>{{ tr("本次仅保存草稿，不会发布单据或触发收款、放行。") }}</p></div>
              <section class="pi-form-card"><div class="pi-section-heading"><h3>{{ tr("客户与商品") }}</h3><el-button text type="primary" @click="goToDraftStep(0)">{{ tr("返回修改") }}</el-button></div><dl class="pi-detail-grid"><div><dt>{{ tr("订单客户") }}</dt><dd>{{ selectedCustomer?.name }}</dd></div><div><dt>{{ tr("贸易 / 币种") }}</dt><dd>{{ draft.tradeType === 'EXPORT' ? tr('外销') : tr('内销') }} · {{ draft.currencyCode.toUpperCase() }}</dd></div><div><dt>{{ tr("所属客户") }}</dt><dd>{{ selectedAgent?.name || tr('未关联') }}</dd></div><div><dt>{{ tr("来源订单") }}</dt><dd>{{ options.orders.find(order => order.id === draft.sourceOrderId)?.orderNo || (fromLocks ? tr('由来源锁定生成') : hasBoundOrders ? tr('已关联履约订单') : draft.document.business?.orderSource === 'NEW_BUSINESS' ? tr('新业务，收款达标后自动生成') : tr('请选择来源订单')) }}</dd></div></dl><div class="pi-review-items"><article v-for="item in draft.items" :key="item.key"><div><strong>{{ options.skus.find(sku => sku.id === item.skuId)?.skuCode }}</strong><span>{{ options.skus.find(sku => sku.id === item.skuId)?.productName }}</span><small>{{ item.quantity }} {{ item.unit }} × {{ money(item.unitPrice) }} {{ trInline("· 折扣") }} {{ money(item.discountAmount) }} {{ trInline("· 税费") }} {{ money(item.taxAmount) }}</small></div><strong>{{ lineTotal(item) == null ? '—' : fromCents(lineTotal(item)!) }}</strong></article></div><p v-if="selectedLocks.length" class="pi-field-help">{{ trInline("关联") }} {{ selectedLocks.length }} {{ trInline("笔来源锁定，沿用原截止时间。") }}</p></section>
              <section class="pi-form-card"><div class="pi-section-heading"><h3>{{ tr("交易与交付") }}</h3><el-button text type="primary" @click="goToDraftStep(1)">{{ tr("返回修改") }}</el-button></div><dl class="pi-detail-grid"><div><dt>{{ tr("模板") }}</dt><dd>{{ draft.document.templateCode }} · {{ draft.document.templateVersion }}</dd></div><div><dt>{{ tr("卖方") }}</dt><dd>{{ draft.document.sellerName }}</dd></div><div><dt>{{ tr("收款人") }}</dt><dd>{{ draft.document.beneficiary }}</dd></div><div><dt>{{ tr("收款银行 / 账号") }}</dt><dd>{{ draft.document.bankName }}<br />{{ draft.document.bankAccount }}</dd></div><div><dt>{{ tr("库存保留门槛") }}</dt><dd>{{ draft.currencyCode.toUpperCase() }} {{ money(draft.depositRequiredAmount) }}</dd></div><div><dt>{{ tr("发货放行门槛") }}</dt><dd>{{ draft.currencyCode.toUpperCase() }} {{ money(draft.shipmentRequiredAmount.trim() || '0') }}</dd></div><div class="pi-span-two"><dt>{{ tr("付款条款") }}</dt><dd>{{ draft.document.paymentTerms }}</dd></div><div class="pi-span-two"><dt>{{ tr("交付条款") }}</dt><dd>{{ draft.document.deliveryTerms }}</dd></div><div><dt>{{ tr("计划发货 / 运输方式") }}</dt><dd>{{ draft.plannedShipDate || tr('待补充') }} · {{ draft.document.transportMode || tr('待补充') }}</dd></div><div><dt>{{ tr("开单日期 / 有效期") }}</dt><dd>{{ draft.document.issueDate || tr('待补充') }} / {{ draft.document.validUntil || tr('待补充') }}</dd></div><div v-if="draft.tradeType === 'EXPORT'"><dt>{{ tr("贸易术语") }}</dt><dd>{{ draft.document.tradeTerms || tr('未填写 / 不适用') }}</dd></div><div class="pi-span-two"><dt>{{ tr("收货地址") }}</dt><dd>{{ draft.document.shippingAddress || tr('待补充') }}</dd></div><div v-if="draft.remark" class="pi-span-two"><dt>{{ tr("备注") }}</dt><dd>{{ draft.remark }}</dd></div></dl></section>
              <div class="pi-save-notice"><strong>{{ tr("保存后还可以继续编辑") }}</strong><p>{{ tr("客户发布、收款确认与发货放行仍须在 PI 详情中分别操作。") }}</p></div>
            </section>
          </el-form>
        </main>
        <aside class="pi-draft-summary" :aria-label='tr("PI 金额摘要")'>
          <div class="pi-summary-caption"><span>{{ tr("本单概览") }}</span><span class="pi-draft-dot">{{ tr(localSaveError ? tr('保存失败') : localSavedAt ? tr('已存本机') : tr('未保存')) }}</span></div>
          <h3>{{ selectedCustomer?.name || tr('等待选择客户') }}</h3><p>{{ draft.tradeType === 'EXPORT' ? tr('外销') : tr('内销') }} · {{ draft.items.filter(item => item.skuId).length }} {{ trInline("款商品 · 数量") }} {{ draftQuantity }}</p>
          <div class="pi-summary-total"><span>{{ trInline("预计总金额 /") }} {{ draft.currencyCode.toUpperCase() || tr('币种待填') }}</span><strong>{{ preview && draft.items.length ? fromCents(preview.total) : '—' }}</strong></div>
          <dl class="pi-summary-breakdown"><div><dt>{{ tr("商品金额") }}</dt><dd>{{ preview ? fromCents(preview.subtotal) : '—' }}</dd></div><div><dt>{{ tr("折扣合计") }}</dt><dd>{{ preview ? '− ' + fromCents(preview.discount) : '—' }}</dd></div><div><dt>{{ tr("税费合计") }}</dt><dd>{{ preview ? fromCents(preview.tax) : '—' }}</dd></div><div><dt>{{ tr("运费") }}</dt><dd>{{ preview ? fromCents(preview.freight) : '—' }}</dd></div><div><dt>{{ tr("其他费用") }}</dt><dd>{{ preview ? fromCents(preview.otherFees) : '—' }}</dd></div></dl>
          <div class="pi-summary-template"><span>{{ tr("单据模板") }}</span><strong>{{ draft.document.templateCode ? draft.document.templateCode + ' · ' + draft.document.templateVersion : tr('第二步选择') }}</strong><small>{{ tr("卖方与银行资料随模板固定带入。") }}</small></div>
          <p class="pi-summary-tip">{{ tr("只录入实际业务约定。未填写的金额不参与预估，不会自动补成交价或付款比例。") }}</p>
        </aside>
      </div>
      <template #footer>
        <div class="pi-draft-footer">
          <div v-if="draftValidation || createError" class="pi-draft-error" role="alert">{{ tr(draftValidation?.message || createError) }}</div>
          <div v-if="localSaveError" class="pi-draft-error" role="alert">{{ tr(localSaveError) }}<el-button v-if="localSaveError.includes('其他页面')" text @click="saveLocalDraftCopy">{{ tr("另存为新草稿") }}</el-button></div>
          <div class="pi-local-save-status" role="status">{{ tr(localSaveStatus) }}<span v-if="localSavedAt"> · {{ new Date(localSavedAt).toLocaleTimeString() }}</span> {{ trInline("· 当前浏览器、当前账号可恢复") }}</div>
          <div class="pi-footer-row">
            <el-button :disabled="actionBusy || restoringDraft" @click="closeDraft()">{{ tr("关闭") }}</el-button>
            <span class="pi-footer-note">{{ trInline("第") }} {{ draftStep + 1 }} {{ trInline("/ 3 步") }}</span>
            <div class="pi-footer-actions">
              <el-button :disabled="actionBusy || restoringDraft" @click="saveLocalDraft">{{ tr("保存本机草稿") }}</el-button>
              <el-button v-if="draftStep > 0" :disabled="actionBusy" @click="goToDraftStep(draftStep - 1)">{{ tr("上一步") }}</el-button>
              <el-button v-if="draftStep < 2" type="primary" :disabled="optionsBusy || customerBusy || actionBusy || restoringDraft" @click="goToDraftStep(draftStep + 1)">{{ draftStep === 0 ? tr('下一步：交易与交付') : tr('下一步：核对信息') }} <span aria-hidden="true">→</span></el-button>
              <el-button v-else type="primary" :loading="actionBusy" :disabled="optionsBusy || customerBusy || customerLoadFailed || Boolean(optionsError) || restoringDraft" @click="saveCreate">{{ tr("确认并保存 PI") }}</el-button>
            </div>
          </div>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="detailVisible" :title="detail ? `${detail.piNo} · V${detail.revision}` : tr('PI 详情')" width="min(1100px, 96vw)" :close-on-click-modal="false">
      <div v-loading="detailBusy" class="pi-detail"><el-alert v-if="operationError" :title="tr(operationError)" type="error" :closable="false" show-icon />
        <template v-if="detail"><div class="pi-detail-heading"><div><el-tag>{{ tr(stateNames[detail.status] ?? detail.status) }}</el-tag><strong>{{ detail.customerName }}</strong><span>{{ detail.tradeType === 'EXPORT' ? tr('外销') : tr('内销') }} · {{ detail.currencyCode }}</span></div><strong class="pi-grand-total">{{ money(detail.totalAmount) }}</strong></div>
          <div class="pi-actions">
            <PiRevisions v-if="internal" :pi="detail" :can-write="canWrite" @select="openDetail" />
            <el-button v-if="canWrite && detail.status === 'DRAFT'" :disabled="actionBusy" @click="openEdit">{{ tr("编辑草稿") }}</el-button>
            <el-button v-if="canWrite && ['DRAFT', 'CONFIRMED'].includes(detail.status)" type="danger" plain :disabled="actionBusy" @click="cancelPi">{{ tr("取消 PI") }}</el-button>
            <el-button v-if="canRenewReservation" :disabled="actionBusy" type="warning" plain @click="openReservation()">{{ tr("核对库存并重新占用") }}</el-button>
            <el-button v-if="canExport" :loading="actionBusy" @click="download('pdf')">{{ tr("下载 PDF") }}</el-button>
            <el-button v-if="canExport" :loading="actionBusy" @click="download('xlsx')">{{ tr("下载 Excel") }}</el-button>
          </div>
          <el-alert v-if="detail.reservationStatus === 'EXPIRED_REQUIRES_REACQUIRE'" :title='tr("来源订单库存占用已到期")' type="warning" :closable="false" show-icon>
            {{ isAgent ? tr('请联系业务人员核对库存并处理占用。') : tr('到账记录保留；请在流程中重试按仓锁库。系统重新核对指定仓库和全局可用库存，历史缺少精确SKU的子单须先核对。') }}
            <span v-if="internal && !canManageReservation">{{ tr("请交由有订单及客户锁库维护权限的业务人员处理。") }}</span>
          </el-alert>
          <el-alert v-if="internal && detail.reservationStatus === 'CREDIT_HELD'" :title="manualWorkflow ? tr('人工确认保留库存，实际到账另行登记') : tr('客户收到货后付款授权保留')" :description='tr("库存已按授权保留；本状态不代表实际收到款项，仍须按 PI 付款条款跟进收款。")' type="info" :closable="false" />
          <el-alert v-if="internal && detail.reservationStatus === 'PAID_HELD'" :title='tr("收款达标保留，待履约或人工关闭")' :description='tr("该占用不按原截止时间自动释放；本状态不代表已全额到账。")' type="success" :closable="false" show-icon />
          <el-alert v-if="detail.reservationStatus === 'RELEASED'" :title='tr("来源订单库存占用已释放")' :description='tr("当前来源占用不能继续用于发货放行，请联系业务人员处理。")' type="warning" :closable="false" show-icon />
          <p v-if="internal && detail.reservationStatus === 'FULFILLED'" class="pi-hint">{{ tr("来源订单已履约。需要撤销出库时，请先核对订单占用及可撤销条件。") }}<a v-if="allowed('ORDER_READ')" href="/orders">{{ tr("查看销售订单") }}</a></p>
          <el-alert v-if="detail.status === 'CANCELLED' && detail.remark?.startsWith('自动取消：')" :title='tr("PI已自动取消")' :description="detail.remark" type="warning" :closable="false" show-icon />
          <p class="pi-hint">{{ trInline("库存占用：") }}{{ tr(reservationLabel(detail.reservationStatus, isAgent)) }}</p>
          <el-descriptions :column="2" border><el-descriptions-item :label='tr("所属客户")'>{{ detail.agentName || '—' }}</el-descriptions-item><el-descriptions-item :label='tr("计划发货")'>{{ detail.plannedShipDate || '—' }}</el-descriptions-item><el-descriptions-item :label='tr("创建时间")'>{{ detail.createdAt }}</el-descriptions-item><el-descriptions-item :label='tr("备注")'>{{ detail.remark || '—' }}</el-descriptions-item></el-descriptions>
          <p v-if="detailProfile">{{ trInline("履约配置：") }}{{ detailProfile.name }} · {{ detailProfile.version }}{{ trInline("；收款主体：") }}{{ detailProfile.collectionEntity }}<span v-if="detail.document?.business?.paymentDueDate">{{ trInline("；付款到期：") }}{{ detail.document.business.paymentDueDate }}</span></p>
          <PiManualWorkflowPanel v-if="internal && manualWorkflow" :workflow="workflow" :busy="workflowBusy || workflowActionBusy || actionBusy" :error="workflowError || releaseError" :can-write="canWrite" :can-release="canRelease" :pi-status="detail.status" @complete="completeWorkflow" @confirm-pi="handleWorkflowAction('CONFIRM_PI')" @release="releaseManualPi" />
          <PiWorkflowPanel v-if="internal && !manualWorkflow" :related-actions="workflowRelatedActions" :workflow="workflow" :rules="detailProfile?.rules" :busy="workflowBusy" :error="workflowError" :action-busy="workflowActionBusy || actionBusy" :can-write="canWrite" :can-pay="canPay" :can-release="canRelease" :can-logistics="canLogistics" :can-after-sales="canAfterSales" :pi-id="detail.id" :logistics="detail.logistics" :domestic-shipment="detail.document?.business?.domesticShipment" :china-export="detailIsChinaExport" :pi-status="detail.status" :currency-code="detail.currencyCode" :gate-met="gateMet" :reservation-blocked="reservationBlocked" @action="handleWorkflowAction" @complete="completeWorkflow" @related="openRelatedSection" @production-update="recordProductionUpdate" @after-sales="emit('navigate', 'after-sales')" />
          <div ref="detailTabsElement"></div>
          <el-button v-if="relatedReturn" link type="primary" :disabled="actionBusy" @click="resumeRelatedForm">{{ tr("返回放行发货") }}</el-button>
          <el-tabs v-model="detailTab">
            <el-tab-pane v-if="internal && showFinance && (europe || detail.tradeType === 'EXPORT')" :label='tr("向 Totem 付款")' name="totem"><SalesSettlementPanel type="PI" :subject-id="detail.id" :currency-code="detail.currencyCode" :context="context" /></el-tab-pane><el-tab-pane :label='tr("商品与金额")' name="items"><el-table :data="detail.items" :empty-text='tr("暂无商品")'>
      <RecordIdColumn /><el-table-column prop="skuCode" label="SKU" min-width="140" /><el-table-column :label='tr("图片")' width="100"><template #default="scope"><PiItemImage v-if="scope.row.imageAttachmentId" :key="`${detail.id}-${scope.row.id}`" :pi-id="detail.id" :item-id="scope.row.id" /><span v-else>{{ tr("未记录") }}</span></template></el-table-column><el-table-column prop="productName" :label='tr("商品")' min-width="140" /><el-table-column prop="specification" :label='tr("规格")' min-width="140" /><el-table-column prop="quantity" :label='tr("数量")' width="80" /><el-table-column prop="unit" :label='tr("单位")' width="80" /><el-table-column :label='tr("单价")' min-width="110"><template #default="scope">{{ money(scope.row.unitPrice) }}</template></el-table-column><el-table-column :label='tr("折扣")' min-width="100"><template #default="scope">{{ money(scope.row.discountAmount) }}</template></el-table-column><el-table-column :label='tr("税费")' min-width="100"><template #default="scope">{{ money(scope.row.taxAmount) }}</template></el-table-column><el-table-column :label='tr("金额")' min-width="120"><template #default="scope">{{ money(scope.row.lineTotal) }}</template></el-table-column></el-table><div class="pi-totals"><span>{{ tr("商品金额") }} <b>{{ money(detail.subtotal) }}</b></span><span>{{ tr("折扣合计") }} <b>− {{ money(detail.discountAmount) }}</b></span><span>{{ tr("税费合计") }} <b>{{ money(detail.taxAmount) }}</b></span><span>{{ tr("运费") }} <b>{{ money(detail.freightAmount) }}</b></span><span>{{ tr("其他费用") }} <b>{{ money(detail.otherFeeAmount ?? '0.00') }}</b></span><span>{{ tr("总金额") }} <b>{{ detail.currencyCode }} {{ money(detail.totalAmount) }}</b></span></div></el-tab-pane>
            <el-tab-pane v-if="detail.fulfillment?.length" :label='tr("履约订单")' name="fulfillment">
              <template v-if="internal && detail.revisionSources?.length">
                <h3>{{ tr("本次修订新增来源") }}</h3><p class="pi-hint">{{ tr("确认修订后显示关联订单。来源截止时间和数量可供核对。") }}</p>
                <el-table :data="detail.revisionSources">
      <RecordIdColumn prop="lockId" :label='tr("来源锁定 ID")' /><el-table-column prop="lockNo" :label='tr("来源锁定")' min-width="180" /><el-table-column prop="skuCode" label="SKU" min-width="120" /><el-table-column prop="quantity" :label='tr("转换数量")' width="100" /><el-table-column prop="expiresAt" :label='tr("来源截止")' min-width="180" /><el-table-column :label='tr("关联订单")' min-width="180"><template #default="{ row }">{{ row.orderId ? detail.fulfillment.find(line => line.orderId === row.orderId)?.orderNo || row.orderId : tr('待确认转换') }}</template></el-table-column></el-table>
              </template>
              <el-table :data="detail.fulfillment">
      <RecordIdColumn prop="orderId" :label='tr("订单 ID")' /><el-table-column prop="orderNo" :label='tr("订单")' min-width="200" /><el-table-column prop="skuCode" label="SKU" min-width="120" /><el-table-column prop="orderedQty" :label='tr("约定")' width="70" /><el-table-column prop="shippedQty" :label='tr("已发")' width="70" /><el-table-column prop="closedQty" :label='tr("关闭")' width="70" /><el-table-column prop="remainingQty" :label='tr("待发")' width="70" /><el-table-column :label='tr("库存占用")' min-width="180"><template #default="{ row }">{{ tr(reservationLabel(row.reservationStatus, isAgent)) }}</template></el-table-column><el-table-column v-if="canManageReservation" :label='tr("操作")' width="130"><template #default="{ row }"><el-button v-if="['TIMED_ACTIVE', 'EXPIRED_REQUIRES_REACQUIRE'].includes(row.reservationStatus)" link :disabled="actionBusy" @click="openReservation(row.orderId)">{{ tr("核对并重新占用") }}</el-button></template></el-table-column></el-table>
            </el-tab-pane>
            <el-tab-pane :label='tr("真实出库批次")' name="batches"><el-table :data="detail.dispatchBatches || []" :empty-text='tr("尚无实际出库批次")'>
      <RecordIdColumn /><el-table-column prop="batchNo" :label='tr("出库批次")' min-width="240" /><el-table-column prop="createdAt" :label='tr("出库时间")' min-width="180" /><el-table-column prop="originalQty" :label='tr("原出库")' width="85" /><el-table-column prop="activeQty" :label='tr("有效数量")' width="90" /><el-table-column :label='tr("来源")' min-width="150"><template #default="{ row }">{{ row.sourceType === 'LEGACY_SINGLE' ? tr('历史单车记录（非原合批）') : row.sourceType === 'PI_COMMAND' ? tr('PI整批出库') : tr('订单批量出库') }}</template></el-table-column><el-table-column :label='tr("状态")' width="100"><template #default="{ row }">{{ row.status === 'COMPLETED' ? tr('已出库') : row.status === 'CANCELLED' ? tr('已撤销') : tr('部分撤销') }}</template></el-table-column><el-table-column :label='tr("车辆")' width="90"><template #default="{ row }"><el-button link @click="openBatch(row.id)">{{ tr("查看") }}</el-button></template></el-table-column></el-table></el-tab-pane>
            <el-tab-pane :label='tr("单据资料与条款")' name="document"><el-descriptions :column="2" border><el-descriptions-item v-for="[key, label] in documentLabels" :key="key" :label="tr(label)"><span class="pi-prewrap">{{ detail.document?.[key] || '—' }}</span></el-descriptions-item></el-descriptions></el-tab-pane>
            <el-tab-pane v-if="showFinance" :label='tr("收款与放行")' name="payment"><div class="pi-tab-toolbar"><strong>{{ tr("到账记录") }}</strong><el-button v-if="canPay && ['CONFIRMED', 'RELEASED'].includes(detail.status)" type="primary" plain :disabled="actionBusy" @click="openPayment">{{ tr("追加到账") }}</el-button></div><el-descriptions :column="2" border><el-descriptions-item :label='tr("累计到账")'>{{ detail.currencyCode }} {{ money(detail.paidAmount) }}</el-descriptions-item><el-descriptions-item :label='tr("收款状态")'>{{ tr(paymentNames[detail.paymentStatus ?? ''] ?? detail.paymentStatus ?? '—') }}</el-descriptions-item><el-descriptions-item :label='tr("定金约定金额")'>{{ money(detail.depositRequiredAmount) }}</el-descriptions-item><el-descriptions-item :label='tr("发货放行门槛")'>{{ money(detail.shipmentRequiredAmount) }}</el-descriptions-item><el-descriptions-item :label='tr("放行状态")'>{{ detail.releaseStatus === 'RELEASED' ? tr('已放行') : tr('未放行') }}</el-descriptions-item><el-descriptions-item :label='tr("放行时间")'>{{ detail.releasedAt || '—' }}</el-descriptions-item></el-descriptions><p v-if="canRelease && detail.status === 'CONFIRMED' && !gateMet" class="pi-hint">{{ tr("请先由有权限人员批准本单“客户收到货后付款”安排。") }}</p><el-table :data="detail.payments" :empty-text='tr("尚无到账记录")'>
      <RecordIdColumn /><el-table-column :label='tr("状态")' width="100"><template #default="scope">{{ scope.row.status === 'REVERSED' ? tr('已撤回登记') : tr('已确认') }}</template></el-table-column><el-table-column :label='tr("收款方式")' min-width="140"><template #default="scope">{{ paymentMethods[scope.row.paymentMethod] || tr('未记录') }} {{ scope.row.paymentMethodNote }}</template></el-table-column><el-table-column prop="recordedAt" :label='tr("登记时间")' min-width="170" /><el-table-column prop="confirmedBy" :label='tr("登记人ID")' width="100" /><el-table-column prop="reversalReason" :label='tr("撤回原因")' min-width="180" /><el-table-column prop="reversedAt" :label='tr("撤回时间")' min-width="170" /><el-table-column prop="reversedBy" :label='tr("撤回人ID")' width="100" /><el-table-column v-if="canReversePayment" :label='tr("更正")' width="110"><template #default="scope"><el-button v-if="scope.row.status === 'CONFIRMED'" link type="danger" @click="openPaymentReversal(scope.row)">{{ tr("撤回错误登记") }}</el-button></template></el-table-column><el-table-column prop="receivedAt" :label='tr("到账时间")' min-width="170" /><el-table-column :label='tr("金额")' min-width="130"><template #default="scope">{{ scope.row.currencyCode }} {{ money(scope.row.amount) }}</template></el-table-column><el-table-column prop="remark" :label='tr("说明")' min-width="220" /><el-table-column :label='tr("收款凭证")' width="120"><template #default="scope"><el-button link type="primary" @click="openReceipt(scope.row)">{{ canPay ? tr('查看 / 上传') : tr('查看') }}</el-button></template></el-table-column></el-table></el-tab-pane>
            <el-tab-pane :label='tr("物流信息")' name="logistics"><div class="pi-tab-toolbar"><strong>{{ tr("运输记录") }}</strong><el-button v-if="canLogistics && ['CONFIRMED', 'RELEASED'].includes(detail.status)" type="primary" plain :disabled="actionBusy" @click="openLogistics()">{{ tr("新增物流记录") }}</el-button></div><el-table :data="detail.logistics" :empty-text='tr("尚无物流记录")'>
      <RecordIdColumn /><el-table-column :label='tr("附件")' width="90"><template #default="{ row }"><el-button link @click="openLogisticsAttachment(row)">{{ tr("查看附件") }}</el-button></template></el-table-column><el-table-column :label='tr("来源依据")' min-width="130"><template #default="{ row }">{{ row.linkType === 'ACTUAL_BATCH' ? tr('真实出库批次') : row.linkType === 'LEGACY_UNLINKED' ? tr('历史手工记录，未关联') : tr('未关联出库的计划') }}</template></el-table-column><el-table-column prop="shippedQtySnapshot" :label='tr("确认发运台数")' width="120" /><el-table-column prop="batchNo" :label='tr("物流批次")' min-width="150" /><el-table-column prop="carrier" :label='tr("承运商")' min-width="130" /><el-table-column prop="trackingNo" :label='tr("运单号")' min-width="170" /><el-table-column :label='tr("状态")' width="100"><template #default="scope">{{ tr(logisticsStates[scope.row.status] || scope.row.status) }}</template></el-table-column><el-table-column prop="shippedAt" :label='tr("发货时间")' min-width="180" /><el-table-column prop="remark" :label='tr("备注")' min-width="180" /><el-table-column v-if="internal" :label='tr("操作")' width="210"><template #default="{ row }"><el-button v-if="canLogistics && row.status !== 'SHIPPED'" link :disabled="actionBusy" @click="openLogistics(row)">{{ tr("编辑 / 确认发运") }}</el-button><el-button v-if="canLogistics && row.status !== 'WITHDRAWN'" link :disabled="actionBusy" @click="withdrawLogistics(row)">{{ tr("撤回") }}</el-button><el-button link @click="openLogisticsHistory(row)">{{ tr("历史") }}</el-button></template></el-table-column></el-table></el-tab-pane>
            <el-tab-pane v-if="canExport" :label='tr("导出任务")' name="exports"><PiExportTask v-if="detailTab === 'exports'" :pi-id="detail.id" :can-retry="internal && canExport" :status="detail.status" /></el-tab-pane>
            <el-tab-pane :label='tr("业务单据附件")' name="confirmations"><PiConfirmationAttachments v-if="detailTab === 'confirmations'" :pi-id="detail.id" :can-upload="canWrite && ['DRAFT', 'CONFIRMED', 'RELEASED'].includes(detail.status)" /></el-tab-pane>
          </el-tabs></template>
      </div><template #footer><el-button @click="detailVisible = false">{{ tr("关闭") }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="logisticsAttachmentVisible" :title='tr("物流附件")' width="min(850px, 95vw)" :close-on-click-modal="false">
      <PiLogisticsAttachments v-if="detail && logisticsAttachment && logisticsAttachmentVisible" :pi-id="detail.id" :logistics-id="logisticsAttachment.id" :can-upload="canLogistics && ['PLANNED', 'SHIPPED'].includes(logisticsAttachment.status)" />
    </el-dialog>
    <PiReceiptAttachments v-if="showFinance && detail && receiptPayment" v-model="receiptVisible" :pi-id="receiptPayment.piId" :payment="receiptPayment" :can-upload="canPay && receiptPayment.status === 'CONFIRMED'" @saved="receiptSaved" />
    <el-dialog v-model="reversalVisible" :title='tr("撤回错误到账登记")' width="520px" :close-on-click-modal="false">
      <p>{{ tr("此操作更正错误登记，不办理退款。原记录和凭证保留，当前 PI 需重新核对并放行。已出库车辆和库存占用保持原状。") }}</p>
      <el-input v-model="reversal.reason" type="textarea" maxlength="500" :placeholder='tr("说明哪笔登记有误及核对依据")' />
      <el-alert v-if="reversalError" :title="tr(reversalError)" type="error" :closable="false" />
      <template #footer><el-button :disabled="actionBusy" @click="reversalVisible = false">{{ tr("取消") }}</el-button><el-button type="danger" :loading="actionBusy" @click="savePaymentReversal">{{ tr("确认撤回登记") }}</el-button></template>
    </el-dialog>
    <el-dialog v-model="logisticsHistoryVisible" :title='tr("物流修订历史")' width="min(1000px, 94vw)"><el-alert v-if="logisticsHistoryError" :title="tr(logisticsHistoryError)" type="error" :closable="false" /><el-table :data="logisticsHistory" v-loading="logisticsHistoryBusy"><el-table-column prop="revision" :label='tr("版本")' width="70" /><el-table-column prop="createdAt" :label='tr("记录时间")' min-width="180" /><el-table-column :label='tr("动作")'><template #default="{ row }">{{ ({ CREATE: '创建', UPDATE: '更新', WITHDRAW: '撤回', MIGRATED_CURRENT: '迁移当时快照' } as Record<string,string>)[row.action] || row.action }}</template></el-table-column><el-table-column prop="carrier" :label='tr("承运商")' /><el-table-column prop="trackingNo" :label='tr("运单号")' /><el-table-column :label='tr("状态")'><template #default="{ row }">{{ tr(logisticsStates[row.status] || row.status) }}</template></el-table-column><el-table-column prop="shippedQtySnapshot" :label='tr("发运数量")' /><el-table-column prop="revokeReason" :label='tr("撤回原因")' min-width="200" /></el-table></el-dialog>
    <el-dialog v-model="batchVisible" :title='tr("出库批次车辆")' width="min(1000px, 94vw)"><el-alert v-if="batchError" :title="tr(batchError)" type="error" :closable="false" /><el-table v-loading="batchBusy" :data="batchVehicles">
      <RecordIdColumn prop="stockOutId" :label='tr("出库记录 ID")' /><el-table-column prop="vehicleQrCode" :label='tr("聚合码")' min-width="240" show-overflow-tooltip /><el-table-column prop="frameSn" :label='tr("车架码")' min-width="180" /><el-table-column prop="skuCode" label="SKU" /><el-table-column prop="orderNo" :label='tr("履约订单")' min-width="180" /><el-table-column prop="stockOutNo" :label='tr("出库单")' min-width="180" /><el-table-column v-if="internal" :label='tr("购销合同方")' min-width="160"><template #default="{ row }">{{ row.purchaseContractParty === 'YINSANHUAN' ? tr('银三环') : row.purchaseContractParty || tr('未登记') }}</template></el-table-column><el-table-column v-if="internal" :label='tr("购销合同号")' min-width="260"><template #default="{ row }"><PurchaseContractNumber :value="row.purchaseContractNo" /></template></el-table-column><el-table-column :label='tr("状态")'><template #default="{ row }">{{ row.status === 'COMPLETED' ? tr('已出库') : tr('已撤销') }}</template></el-table-column><el-table-column prop="cancelReason" :label='tr("撤销原因")' /></el-table></el-dialog>
    <el-dialog v-model="reservationVisible" :title='tr("核对库存并重新占用")' width="540px" :close-on-click-modal="false" :close-on-press-escape="!actionBusy">
      <p>{{ tr("提交时重新检查可用库存。已经到期的占用不会因登记收款而自动恢复；库存不足时本次操作会被拒绝。") }}</p>
      <p class="pi-hint">{{ tr("如关联的已确认 PI 已达到保留库存门槛，成功占用后将按收款达标规则保留，待履约或人工关闭。") }}</p>
      <el-form label-position="top" @submit.prevent="saveReservation"><el-form-item :label='tr("未达保留门槛时的占用时长（小时）")' required><el-input-number v-model="reservation.hours" :min="1" :max="720" :precision="0" /></el-form-item><el-form-item :label='tr("重新占用原因")' required><el-input v-model="reservation.reason" type="textarea" maxlength="500" show-word-limit /></el-form-item></el-form>
      <el-alert v-if="reservationError" :title="tr(reservationError)" type="error" :closable="false" show-icon />
      <template #footer><el-button :disabled="actionBusy" @click="reservationVisible = false">{{ tr("取消") }}</el-button><el-button type="primary" :loading="actionBusy" @click="saveReservation">{{ tr("确认重新占用") }}</el-button></template>
    </el-dialog>
    <el-dialog v-model="paymentVisible" :title='tr("登记到账")' width="520px" :close-on-click-modal="false"><el-form label-position="top"><el-form-item :label='tr("到账金额")' required><el-input v-model="payment.amount" inputmode="decimal"><template #append>{{ payment.currencyCode }}</template></el-input></el-form-item><el-form-item :label='tr("到账时间")'><el-date-picker v-model="payment.receivedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" :placeholder='tr("留空使用当前时间")' /></el-form-item><el-form-item :label='tr("收款方式")' required><el-select v-model="payment.paymentMethod"><el-option v-for="(label, key) in paymentMethods" :key="key" :value="key" :label="tr(label)" /></el-select></el-form-item><el-form-item v-if="payment.paymentMethod === 'OTHER'" :label='tr("其他方式说明")' required><el-input v-model="payment.paymentMethodNote" maxlength="200" /></el-form-item><el-form-item :label='tr("到账说明")'><el-input v-model="payment.remark" type="textarea" maxlength="500" :placeholder='tr("到账来源、银行流水说明等")' /></el-form-item></el-form><el-alert v-if="paymentError" :title="tr(paymentError)" type="error" :closable="false" /><template #footer><el-button :disabled="actionBusy" @click="paymentVisible = false">{{ tr("取消") }}</el-button><el-button type="primary" :loading="actionBusy" @click="savePayment">{{ tr("确认到账") }}</el-button></template></el-dialog>
    <el-dialog v-model="releaseVisible" :title='tr("放行发货")' width="min(760px, 95vw)" :close-on-click-modal="false">
      <p>{{ tr("请核对合同、指定仓库库存及收款凭证；已确认的每笔收款须上传凭证后再放行出库。") }}</p>
      <PiRelatedActions v-if="releaseError" :actions="workflowRelatedActions" step="RELEASE_PI" :busy="actionBusy" @select="openRelatedSection" />
      <section class="release-receipts">
        <div class="release-receipts-head"><strong>{{ tr("收款凭证") }}</strong><el-button link type="primary" :loading="releaseReceiptsBusy" @click="loadReleaseReceipts">{{ tr("刷新状态") }}</el-button></div>
        <el-table :data="confirmedReleasePayments" v-loading="releaseReceiptsBusy" :empty-text='tr("当前没有已确认到账记录")'>
          <el-table-column prop="receivedAt" :label='tr("到账时间")' min-width="170" />
          <el-table-column :label='tr("金额")' min-width="130"><template #default="scope">{{ scope.row.currencyCode }} {{ money(scope.row.amount) }}</template></el-table-column>
          <el-table-column :label='tr("凭证状态")' min-width="130"><template #default="scope"><el-tag :type="receiptCheckType(scope.row)">{{ tr(receiptCheckLabel(scope.row)) }}</el-tag></template></el-table-column>
          <el-table-column :label='tr("操作")' width="130"><template #default="scope"><el-button link type="primary" :disabled="releaseReceiptsBusy" @click="openReceipt(scope.row)">{{ canPay ? (releaseReceiptChecks[scope.row.id]?.count ? tr('查看 / 补传') : tr('快速上传')) : tr('查看凭证') }}</el-button></template></el-table-column>
        </el-table>
        <el-alert v-if="missingReleaseReceiptCount" :title="tr('还有 {value0} 笔已确认到账缺少收款凭证，请逐笔上传', { value0: missingReleaseReceiptCount })" type="warning" :closable="false" show-icon />
        <el-alert v-if="!canPay && missingReleaseReceiptCount" :title='tr("当前账号没有收款凭证上传权限，请由具备收款确认权限的人员上传")' type="info" :closable="false" show-icon />
      </section>
      <el-form label-position="top"><el-form-item :label='tr("放行说明")' required><el-input v-model="releaseReason" type="textarea" maxlength="500" /></el-form-item></el-form>
      <el-alert v-if="releaseError" :title="tr(releaseError)" type="error" :closable="false" />
      <template #footer><el-button :disabled="actionBusy" @click="releaseVisible = false">{{ tr("取消") }}</el-button><el-button type="success" :loading="actionBusy" :disabled="releaseReceiptsBusy || releaseReceiptCheckFailed || missingReleaseReceiptCount > 0" @click="saveRelease">{{ tr("确认放行") }}</el-button></template>
    </el-dialog>
    <el-dialog v-model="logisticsVisible" :title='tr("登记物流批次")' width="min(560px, 95vw)" :close-on-click-modal="false">
      <p v-if="!detail?.dispatchBatches?.some(batch => batch.activeQty > 0)" class="pi-hint">{{ tr("暂无真实出库批次，请由仓库实际出库后生成。") }}<a v-if="allowed('STOCK_READ')" href="/stock-out" target="_blank" rel="noopener noreferrer">{{ tr("去查看出库记录") }}</a></p>
      <el-form label-position="top"><el-form-item :label='tr("真实出库批次")'><el-select v-model="logistics.stockOutBatchId" clearable :placeholder='tr("确认发运时必选")'><el-option v-for="batch in detail?.dispatchBatches || []" :key="batch.id" :value="batch.id" :disabled="batch.activeQty === 0" :label="tr('{value0} · 有效 {value1} 台', { value0: batch.batchNo, value1: batch.activeQty })" /></el-select></el-form-item><el-form-item :label='tr("物流批次 / 计划编号")' required><el-input v-model="logistics.batchNo" :disabled="Boolean(logistics.stockOutBatchId)" maxlength="64" /></el-form-item><el-form-item :label='tr("物流状态")' required><el-select v-model="logistics.status"><el-option value="PLANNED" :label='tr("计划中")' /><el-option value="SHIPPED" :label='tr("已发货")' :disabled="detail?.status !== 'RELEASED'" /></el-select></el-form-item><el-form-item :label='tr("承运商")' :required="logistics.status === 'SHIPPED' && !customerArrangedLogistics"><el-input v-model="logistics.carrier" maxlength="128" /></el-form-item><el-form-item :label='tr("运单号")' :required="logistics.status === 'SHIPPED' && !customerArrangedLogistics"><el-input v-model="logistics.trackingNo" maxlength="128" /></el-form-item><el-form-item :label='tr("备注")'><el-input v-model="logistics.remark" type="textarea" maxlength="500" /></el-form-item></el-form><el-alert v-if="logisticsError" :title="tr(logisticsError)" type="error" :closable="false" /><template #footer><el-button :disabled="actionBusy" @click="logisticsVisible = false">{{ tr("取消") }}</el-button><el-button type="primary" :loading="actionBusy" @click="saveLogistics">{{ tr("保存物流") }}</el-button></template></el-dialog>
  </section>
</template>

<style scoped>
.pi-list-facets {
  --pi-filter-control-height: 32px;
  margin-bottom: 14px;
  padding: 4px 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  background: var(--el-bg-color);
}
.pi-filter-row {
  display: grid;
  grid-template-columns: minmax(96px, max-content) minmax(0, 1fr);
  align-items: center;
  gap: 12px;
  padding: 4px 0;
}
.pi-filter-row + .pi-filter-row { border-top: 1px solid var(--el-border-color-extra-light); }
.pi-filter-label { font-size: 13px; font-weight: 600; color: var(--el-text-color-regular); }
.pi-filter-choices { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; }
.pi-filter-choices button {
  min-height: var(--pi-filter-control-height);
  padding: 3px 10px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--el-text-color-regular);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
.pi-filter-choices button:hover { background: var(--el-fill-color-light); }
.pi-filter-choices button:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 1px; }
.pi-filter-choices button[aria-pressed="true"] {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
}
.pi-filter-choices :deep(.pi-filter-month) {
  width: 140px;
  max-width: 100%;
  height: var(--pi-filter-control-height);
}
.pi-filter-choices :deep(.pi-filter-month .el-input__wrapper) {
  box-sizing: border-box;
  min-height: 100%;
}
.pi-filter-hint { font-size: 12px; color: var(--el-text-color-secondary); }
@media (max-width: 580px) {
  .pi-list-facets { --pi-filter-control-height: 44px; }
  .pi-filter-row { grid-template-columns: minmax(0, 1fr); gap: 2px; padding: 6px 0; }
}
.europe-order-scope{display:flex;align-items:center;flex-wrap:wrap;gap:12px;padding:16px 18px;margin-bottom:18px;border:1px solid #dbe4ee;border-radius:10px;background:#f6f9fc}.europe-order-scope label{font-weight:600;white-space:nowrap}.europe-order-scope .el-select{width:320px;max-width:100%}.europe-order-scope p{flex-basis:100%;margin:0;color:#65758a;font-size:13px;line-height:1.7}.europe-order-scope .el-alert{width:100%;margin:0}
.pi-workspace{padding:28px 32px;min-width:0;max-width:1500px;margin:auto}.pi-heading{display:flex;justify-content:space-between;gap:20px;align-items:center;margin-bottom:24px}.pi-heading h2{margin:0 0 7px;font-size:24px}.pi-heading p,.pi-hint{color:#65758a;font-size:13px;line-height:1.7}.pi-heading p{margin:0}.pi-filters{display:flex;gap:12px;align-items:center;margin-bottom:18px}.pi-filters>.el-input{max-width:340px}.pi-filters>.el-select{width:160px}.el-pagination{margin-top:20px;justify-content:flex-end;flex-wrap:wrap}.el-alert{margin:12px 0}.pi-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:0 18px}.pi-form .el-select,.pi-form .el-date-editor,.el-form-item .el-date-editor{width:100%}.pi-form h3{font-size:16px;margin:22px 0 14px}.pi-items-scroll{overflow-x:auto;margin-bottom:12px}.pi-draft-items{min-width:1020px}.pi-draft-items th,.pi-draft-items td{padding:10px 6px}.pi-draft-items th:first-child{width:340px}.pi-draft-items .el-input-number{width:108px}.pi-draft-items .el-input{min-width:98px}.pi-draft-items .el-select{min-width:320px}.pi-amounts{margin-top:24px}.pi-preview{background:#f1f7fc;border-radius:8px;padding:14px 18px;display:flex;flex-direction:column;gap:7px;align-self:start}.pi-preview span{font-size:12px;color:#52687d}.pi-preview strong{font-size:23px;color:#1677c8}.pi-preview small{font-size:11px;line-height:1.6;color:#748196}.pi-detail{min-height:140px}.pi-detail-heading{display:flex;justify-content:space-between;align-items:center;gap:20px;margin:8px 0 18px}.pi-detail-heading>div{display:flex;gap:12px;flex-wrap:wrap;align-items:center}.pi-detail-heading span{font-size:13px;color:#65758a}.pi-grand-total{font-size:24px;color:#1677c8}.pi-actions{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:18px}.pi-actions .el-button+.el-button{margin-left:0}.pi-tab-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:12px}.pi-totals{display:flex;justify-content:flex-end;gap:22px;flex-wrap:wrap;background:#f6f9fc;padding:18px;margin-top:16px}.pi-totals span{display:flex;flex-direction:column;gap:8px;color:#65758a;font-size:12px}.pi-totals b{font-size:15px;color:#23364d}.pi-prewrap{white-space:pre-wrap;overflow-wrap:anywhere}.el-tabs{margin-top:18px}@media(max-width:900px){.pi-workspace{padding:22px 18px}.pi-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.pi-filters{flex-wrap:wrap}}@media(max-width:580px){.pi-grid{grid-template-columns:1fr}.pi-heading{align-items:flex-start;flex-direction:column}.pi-detail-heading{align-items:flex-start;flex-direction:column}.pi-filters>.el-input{max-width:none;width:100%}.pi-grand-total{font-size:21px}}
.release-receipts{margin:16px 0 20px}.release-receipts-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:8px}.release-receipts .el-alert{margin:10px 0 0}
.pi-lock-empty{padding:18px 12px;line-height:1.8;color:#65758a;font-size:13px;text-align:center}.pi-lock-empty strong{display:block;color:#3b4a5c;font-size:14px;margin-bottom:4px}.pi-lock-empty ul{display:inline-block;margin:4px 0;padding-left:18px;text-align:left}.pi-lock-empty p{margin:4px 0}.pi-lock-empty .el-button{margin-top:8px}
</style>
