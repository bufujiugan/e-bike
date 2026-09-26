<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { hasPermission, hasRole, type PermissionContext } from '../permissions'
import { piApi, reservationLabel, type Money, type PiCreate, type PiDocument, type PiOptions, type PiPayment, type PiView, type PiLockOption, type PiLogistics } from '../pi-api'
import PiReceiptAttachments from './PiReceiptAttachments.vue'
import PiTemplates from './PiTemplates.vue'
import PiExportTask from './PiExportTask.vue'
import PiItemImage from './PiItemImage.vue'
import PiConfirmationAttachments from './PiConfirmationAttachments.vue'
import PiLogisticsAttachments from './PiLogisticsAttachments.vue'
import PiRevisions from './PiRevisions.vue'
import PiBusinessDashboard from './PiBusinessDashboard.vue'
import type { PiTemplate } from '../pi-api'
import { customerProfileApi } from '../customer-profile-api'

const props = defineProps<{ context: (PermissionContext & { userType?: string }) | null }>()
const isAgent = computed(() => hasRole(props.context, 'WEB_AGENT') || props.context?.userType === 'AGENT')
const isPda = computed(() => hasRole(props.context, 'PDA_OPERATOR') || props.context?.userType === 'PDA_OPERATOR')
const internal = computed(() => !isAgent.value && !isPda.value)
const allowed = (permission: string) => internal.value && hasPermission(props.context, permission)
const canRead = computed(() => !isPda.value && hasPermission(props.context, 'PI_READ'))
const canWrite = computed(() => allowed('PI_WRITE'))
const canManageTemplates = computed(() => allowed('PI_TEMPLATE_MANAGE'))
const templateVisible = ref(false)
const fixedDocumentFields = ['sellerName', 'sellerAddress', 'sellerContact', 'sellerCountry', 'sellerContactName', 'sellerPhone', 'sellerEmail', 'bankName', 'bankAccount', 'beneficiary', 'templateCode', 'templateVersion']
function chooseTemplate(value: PiTemplate) {
  for (const key of fixedDocumentFields as (keyof PiDocument)[]) draft.document[key] = value[key as keyof PiTemplate] as string
}
const canPay = computed(() => allowed('PI_PAYMENT_CONFIRM'))
const canReversePayment = computed(() => allowed('PI_PAYMENT_REVERSE'))
const canRelease = computed(() => allowed('PI_RELEASE'))
const canManageReservation = computed(() => allowed('ORDER_WRITE') && allowed('AGENT_LOCK_WRITE'))
const canLogistics = computed(() => allowed('PI_LOGISTICS_WRITE'))
const logisticsAttachment = ref<PiLogistics | null>(null)
const logisticsAttachmentVisible = ref(false)
function openLogisticsAttachment(row: PiLogistics) { logisticsAttachment.value = row; logisticsAttachmentVisible.value = true }
const canExport = computed(() => hasPermission(props.context, 'PI_EXPORT'))
const reportRefresh = ref(0), exportBusy = ref(false)
const showFinance = computed(() => canPay.value || canRelease.value || canReversePayment.value)
const rows = ref<PiView[]>([]), total = ref(0), page = ref(1), pageSize = ref(20)
const keyword = ref(''), status = ref(''), busy = ref(false), loadError = ref('')
const detail = ref<PiView | null>(null), detailVisible = ref(false), detailBusy = ref(false), detailTab = ref('items')
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
const fixedItems = computed(() => fromLocks.value || Boolean(editingId.value && detail.value?.fulfillment?.length && !detail.value.sourceOrderId))
const editingRevision = computed(() => Boolean(editingId.value && detail.value?.previousRevisionId))
const revisionFulfillment = computed(() => editingRevision.value && Boolean(detail.value?.fulfillment?.length))
const options = ref<PiOptions>({ customers: [], agents: [], skus: [], orders: [] })
const optionsError = ref(''), createError = ref(''), operationError = ref('')
const optionKeyword = ref('')
const stateNames: Record<string, string> = { DRAFT: '草稿', CONFIRMED: '已确认', RELEASED: '已放行', CANCELLED: '已作废', SUPERSEDED: '历史版本' }
const paymentNames: Record<string, string> = { UNPAID: '未收款', PARTIAL: '部分到账', DEPOSIT_MET: '达到定金门槛', PAID: '全额到账' }
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
  return { sellerName: '', sellerAddress: '', sellerContact: '', buyerAddress: '', buyerContact: '', bankName: '', bankAccount: '', beneficiary: '', paymentTerms: '', deliveryTerms: '', templateCode: '', templateVersion: '' }
}
type DraftItem = { key: string; skuId: number | undefined; quantity: number; unitPrice: string; discountAmount: string; taxAmount: string; unit: string; imageAttachmentId?: string | null }
function newItem(): DraftItem { return { key: crypto.randomUUID(), skuId: undefined, quantity: 1, unitPrice: '', discountAmount: '0.00', taxAmount: '0.00', unit: '台' } }
const draft = reactive({ customerId: undefined as number | undefined, agentId: undefined as number | undefined, sourceOrderId: undefined as number | undefined, tradeType: 'DOMESTIC', currencyCode: 'CNY', plannedShipDate: '', discountAmount: '0.00', taxAmount: '0.00', freightAmount: '0.00', otherFeeAmount: '0.00', depositRequiredAmount: '', shipmentRequiredAmount: '', remark: '', document: emptyDocument(), items: [newItem()] })
const filteredOrders = computed(() => options.value.orders.filter(order => order.customerId === draft.customerId))
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
watch(() => draft.customerId, () => { if (editingId.value == null && !filteredOrders.value.some(order => order.id === draft.sourceOrderId)) draft.sourceOrderId = undefined })
watch(() => draft.sourceOrderId, (id) => { const source = options.value.orders.find(order => order.id === id); if (source) draft.agentId = source.agentId ?? undefined })
watch([() => draft.customerId, () => draft.agentId], () => {
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
  const version = ++detailVersion; detail.value = null; detailVisible.value = true; detailBusy.value = true; detailTab.value = 'items'; operationError.value = ''
  try { const result = await piApi.get(row.id); if (version === detailVersion) detail.value = result }
  catch (error) { if (version === detailVersion) operationError.value = message(error, 'PI 详情加载失败') }
  finally { if (version === detailVersion) detailBusy.value = false }
}
async function loadOptions() {
  optionsBusy.value = true; optionsError.value = ''
  try {
    const found = await piApi.options(optionKeyword.value.trim())
    const keepSelected = <T extends { id: number }>(fresh: T[], previous: T[], selected: (number | undefined)[]) => [...fresh, ...previous.filter(item => selected.includes(item.id) && !fresh.some(match => match.id === item.id))]
    options.value = {
      customers: keepSelected(found.customers, options.value.customers, [draft.customerId]),
      agents: keepSelected(found.agents, options.value.agents, [draft.agentId]),
      skus: keepSelected(found.skus, options.value.skus, draft.items.map(item => item.skuId)),
      orders: keepSelected(found.orders, options.value.orders, [draft.sourceOrderId]),
    }
  }
  catch (error) { optionsError.value = message(error, 'PI 基础资料加载失败') }
  finally { optionsBusy.value = false }
}
async function openCreate() {
  if (!canWrite.value) return
  customerLoadVersion++; customerBusy.value = false; customerLoadFailed.value = false
  editingId.value = null
  fromLocks.value = false; selectedLocks.value = []; conversionKey = ''; conversionFingerprint = ''
  Object.assign(draft, { customerId: undefined, agentId: undefined, sourceOrderId: undefined, tradeType: 'DOMESTIC', currencyCode: 'CNY', plannedShipDate: '', discountAmount: '0.00', taxAmount: '0.00', freightAmount: '0.00', otherFeeAmount: '0.00', depositRequiredAmount: '', shipmentRequiredAmount: '', remark: '', document: emptyDocument(), items: [newItem()] })
  createError.value = ''; optionKeyword.value = ''; createVisible.value = true; await loadOptions()
}
async function openConversion() { await openCreate(); fromLocks.value = true; draft.items = [] }
async function openEdit() {
  const current = detail.value
  if (!canWrite.value || !current || current.status !== 'DRAFT') return
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
    remark: current.remark || '', document: { ...emptyDocument(), ...current.document },
    items: current.items.map(item => ({ key: crypto.randomUUID(), skuId: item.skuId, quantity: item.quantity, unitPrice: money(item.unitPrice), discountAmount: money(item.discountAmount), taxAmount: money(item.taxAmount), unit: item.unit || '台', imageAttachmentId: item.imageAttachmentId })),
  })
  options.value = {
    customers: [{ id: current.customerId, code: current.customerCode || '', name: current.customerName }],
    agents: current.agentId ? [{ id: current.agentId, code: '', name: current.agentName || '所属代理商' }] : [],
    orders: current.sourceOrderId ? [{ id: current.sourceOrderId, orderNo: '原来源订单', customerId: current.customerId, agentId: current.agentId, status: 'CONFIRMED' }] : [],
    skus: current.items.map(item => ({ id: item.skuId, productModelId: item.productModelId, skuCode: item.skuCode, productName: item.productName, specification: item.specification })),
  }
  createError.value = ''; optionKeyword.value = ''; createVisible.value = true; await loadOptions()
  selectedLocks.value = (current.revisionSources || []).filter(source => !source.orderId).map(source => ({ id: source.lockId, skuId: source.skuId, quantity: source.quantity, remainingQty: source.quantity, lockNo: source.lockNo, skuCode: source.skuCode, productName: source.productName, modelId: current.items.find(item => item.skuId === source.skuId)?.productModelId || 0, expireTime: source.expiresAt }))
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
  if (!canWrite.value || actionBusy.value) return
  if (customerBusy.value || customerLoadFailed.value) { createError.value = '客户商务资料尚未成功带入，请重新选择客户后再保存'; return }
  createError.value = ''
  try {
    const body = payload(); actionBusy.value = true
    let created: PiView
    if (fromLocks.value) {
      if (!selectedLocks.value.length) throw new Error('请选择至少一条来源SKU锁定')
      const sources = selectedLocks.value.map(lock => ({ lockId: lock.id, quantity: lock.quantity })).sort((a,b) => a.lockId - b.lockId)
      const fingerprint = JSON.stringify({ sources, pi: body })
      if (fingerprint !== conversionFingerprint) { conversionFingerprint = fingerprint; conversionKey = crypto.randomUUID() }
      created = await piApi.convert({ requestId: conversionKey, sources, pi: body })
    } else created = editingId.value == null ? await piApi.create(body) : await piApi.update(editingId.value, body)
    createVisible.value = false; await load(); await openDetail(created); ElMessage.success('PI 草稿已保存')
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
  if (!detail.value || !canWrite.value) return
  const doc = detail.value.document
  if (!doc?.sellerName?.trim() || !doc.paymentTerms?.trim() || !doc.deliveryTerms?.trim() || !doc.templateCode?.trim() || !doc.templateVersion?.trim()) { operationError.value = '确认前必须填写卖方名称、付款条款、交付条款、模板名称及版本。'; return }
  try {
    await ElMessageBox.confirm('确认后，单据将向所属代理商发布。请核对商品、金额、双方资料和条款。', '确认 PI', { confirmButtonText: '确认发布', cancelButtonText: '返回核对', type: 'warning' })
    actionBusy.value = true; operationError.value = ''; detail.value = await piApi.confirm(detail.value.id); await load(); ElMessage.success('PI 已确认')
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
function openPayment() { if (!detail.value || !canPay.value) return; Object.assign(payment, { requestId: crypto.randomUUID(), amount: '', currencyCode: detail.value.currencyCode, receivedAt: '', remark: '', paymentMethod: '', paymentMethodNote: '' }); paymentError.value = ''; paymentVisible.value = true }
async function savePayment() {
  if (!detail.value || !canPay.value || actionBusy.value) return
  if (!payment.paymentMethod || payment.paymentMethod === 'OTHER' && !payment.paymentMethodNote.trim()) { paymentError.value = '请选择收款方式，其他方式须填写说明'; return }
  const amount = cents(payment.amount)
  if (amount == null || amount < 1n) { paymentError.value = '请输入大于 0 且最多两位小数的到账金额'; return }
  actionBusy.value = true; paymentError.value = ''
  try { detail.value = await piApi.payment(detail.value.id, { ...payment, amount: fromCents(amount), receivedAt: payment.receivedAt || null }); paymentVisible.value = false; detailTab.value = 'payment'; await load(); ElMessage.success('到账记录已保存，可在该笔到账的凭证入口上传文件') }
  catch (error) { paymentError.value = message(error, '收款登记失败，保留当前表单可安全重试') }
  finally { actionBusy.value = false }
}
const reversalVisible = ref(false), reversalError = ref('')
const reversal = reactive({ paymentId: 0, requestId: '', revision: 1, reason: '' })
function openPaymentReversal(row: PiPayment) {
  if (!canReversePayment.value || row.status !== 'CONFIRMED') return
  Object.assign(reversal, { paymentId: row.id, requestId: crypto.randomUUID(), revision: row.revision, reason: '' }); reversalError.value = ''; reversalVisible.value = true
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
const gateMet = computed(() => { if (!detail.value) return false; const paid = cents(String(detail.value.paidAmount ?? '')), gate = cents(String(detail.value.shipmentRequiredAmount ?? '')); return paid != null && gate != null && paid >= gate })
function openRelease() { releaseReason.value = ''; releaseError.value = ''; releaseVisible.value = true }
async function saveRelease() {
  if (!detail.value || !canRelease.value || actionBusy.value) return
  if (reservationBlocked.value) { releaseError.value = '来源订单占用已失效，请先处理库存占用'; return }
  if (!releaseReason.value.trim()) { releaseError.value = '请填写发货放行说明'; return }
  actionBusy.value = true; releaseError.value = ''
  try { detail.value = await piApi.release(detail.value.id, releaseReason.value.trim()); releaseVisible.value = false; await load(); ElMessage.success('PI 已放行发货') }
  catch (error) { releaseError.value = message(error, '发货放行失败') }
  finally { actionBusy.value = false }
}
const logisticsVisible = ref(false), logisticsError = ref('')
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
    detail.value = await piApi.withdrawLogistics(detail.value.id,row.id,{ requestId: crypto.randomUUID(), expectedRevision: row.revision, reason: answer.value.trim() }); await load()
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
  if (logistics.status === 'SHIPPED' && (!logistics.stockOutBatchId || !logistics.carrier.trim() || !logistics.trackingNo.trim())) { logisticsError.value = '确认发运须选择实际出库批次并填写承运商及运单号'; return }
  actionBusy.value = true; logisticsError.value = ''
  try {
    const fingerprint = JSON.stringify({ piId: detail.value.id, logisticsId: logisticsEditingId.value, ...logistics })
    if(fingerprint !== logisticsFingerprint) { logisticsFingerprint = fingerprint; logisticsKey = crypto.randomUUID() }
    const body = { ...logistics, stockOutBatchId: logistics.stockOutBatchId ?? null, requestId: logisticsKey }
    detail.value = logisticsEditingId.value ? await piApi.updateLogistics(detail.value.id,logisticsEditingId.value,body) : await piApi.logistics(detail.value.id,body)
    logisticsVisible.value = false; await load(); ElMessage.success('物流记录已保存')
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
onMounted(() => { if (canRead.value) void load() })
</script>

<template>
  <el-dialog v-model="templateVisible" title="PI模板与银行资料" width="900px" destroy-on-close><PiTemplates v-if="templateVisible" :can-manage="canManageTemplates" /></el-dialog>
  <section class="pi-workspace">
    <el-empty v-if="!canRead" description="当前账号没有 PI 查看权限" />
    <template v-else>
      <div class="pi-heading"><div><h2>{{ isAgent ? '我的 PI 单据' : 'PI 与销售履约' }}</h2><p>{{ isAgent ? '查看已发布的单据、商品和物流进度。' : '维护商品与交易条款，跟进收款、放行和物流。' }}</p></div></div>
      <PiBusinessDashboard v-if="internal" :refresh="reportRefresh" @open-pi="id => openDetail({ id })" />
      <form class="pi-filters list-toolbar" @submit.prevent="search"><el-input v-model="keyword" aria-label="搜索PI、客户、SKU、订单、出库单或物流单号" placeholder="PI / 客户 / SKU / 订单 / 出库单 / 物流单号" clearable /><el-select v-model="status" aria-label="PI 状态" clearable placeholder="全部状态"><el-option v-for="s in states" :key="s" :value="s" :label="stateNames[s]" /></el-select><el-button native-type="submit" type="primary" :loading="busy">查询</el-button><el-button @click="keyword = ''; status = ''; search()">重置</el-button><el-button v-if="canExport" :loading="exportBusy" @click="exportList">导出当前查询全部 PI</el-button><el-button v-if="canManageTemplates" @click="templateVisible = true">模板与银行资料</el-button><el-button v-if="canWrite && canManageReservation" @click="openConversion">从 SKU 锁定生成 PI</el-button><el-button v-if="canWrite" type="primary" @click="openCreate">新建 PI</el-button></form>
      <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false" />
      <el-table v-loading="busy" :data="rows" empty-text="没有符合条件的 PI" row-key="id">
        <el-table-column prop="piNo" label="PI 编号" min-width="200"><template #default="scope"><el-button link type="primary" @click="openDetail(scope.row)">{{ scope.row.piNo }}</el-button></template></el-table-column>
        <el-table-column prop="revision" label="版本" width="70" /><el-table-column prop="customerName" label="客户" min-width="150" />
        <el-table-column label="贸易" width="80"><template #default="scope">{{ scope.row.tradeType === 'EXPORT' ? '外销' : '内销' }}</template></el-table-column>
        <el-table-column label="总金额" min-width="145"><template #default="scope">{{ scope.row.currencyCode }} {{ money(scope.row.totalAmount) }}</template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="scope"><el-tag :type="scope.row.status === 'RELEASED' ? 'success' : scope.row.status === 'DRAFT' ? 'info' : 'primary'">{{ stateNames[scope.row.status] ?? scope.row.status }}</el-tag></template></el-table-column>
        <el-table-column v-if="showFinance" label="收款状态" min-width="125"><template #default="scope">{{ paymentNames[scope.row.paymentStatus] ?? scope.row.paymentStatus ?? '—' }}</template></el-table-column>
        <el-table-column label="库存占用" min-width="185"><template #default="scope">{{ reservationLabel(scope.row.reservationStatus, isAgent) }}</template></el-table-column>
        <el-table-column label="发货进度" min-width="140"><template #default="{ row }">{{ shipmentProgress(row) }}</template></el-table-column>
        <el-table-column prop="businessOwner" label="负责人" min-width="120" /><el-table-column prop="createdAt" label="创建时间" min-width="180" />
        <el-table-column prop="plannedShipDate" label="计划发货" width="120" /><el-table-column label="操作" width="90" fixed="right"><template #default="scope"><el-button link type="primary" @click="openDetail(scope.row)">查看详情</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[10, 20, 50, 100]" :total="total" layout="total, sizes, prev, pager, next" @current-change="load" @size-change="search" />
    </template>

    <el-dialog v-model="createVisible" :title="fromLocks ? '从锁定生成订单及 PI' : editingId == null ? '新建 PI 草稿' : '编辑 PI 草稿'" width="min(1180px, 96vw)" :close-on-click-modal="false" :close-on-press-escape="!actionBusy">
      <form class="pi-filters" @submit.prevent="loadOptions"><el-input v-model="optionKeyword" aria-label="搜索 PI 基础资料" placeholder="输入客户、代理商、SKU 或订单编号查找" clearable /><el-button native-type="submit" :loading="optionsBusy">查找资料</el-button></form><p class="pi-hint">每类显示最多 200 条资料，可通过编号或名称查找；已选项目会保留。</p>
      <el-alert v-if="optionsError" :title="optionsError" type="error" :closable="false"><el-button link @click="loadOptions">重试加载</el-button></el-alert>
      <section v-if="fromLocks || revisionFulfillment && canManageReservation">
        <p v-if="fromLocks" class="pi-hint">先选择客户及代理商，再选择来源锁定。本次会同时生成各SKU的履约订单和一份PI草稿；确认PI时一起确认履约订单，保留每笔锁定原截止。</p>
        <p v-if="revisionFulfillment" class="pi-hint">新增SKU或新增独立履约子单时选择同客户来源锁定，数量计入下方商品总数量；草稿不转换，确认修订才生效并沿用原截止时间。删除来源选择不会自动删除商品行。</p>
        <div class="pi-filters"><el-input v-model="lockKeyword" placeholder="按锁定单号或 SKU 搜索" /><el-button :disabled="!draft.customerId" :loading="lockBusy" @click="lockPage = 1; loadLocks()">查找来源锁定</el-button></div>
        <el-alert v-if="lockError" :title="lockError" type="error" :closable="false" />
        <el-table :data="lockRows" v-loading="lockBusy" empty-text="选择客户后查询可转换的SKU锁定">
          <el-table-column prop="lockNo" label="来源锁定" min-width="180" /><el-table-column prop="skuCode" label="SKU" /><el-table-column prop="remainingQty" label="剩余可转" width="100" /><el-table-column prop="expireTime" label="原截止" min-width="170" />
          <el-table-column label="选择" width="90"><template #default="{ row }"><el-button link :disabled="selectedLocks.some(item => item.id === row.id)" @click="addLock(row)">加入</el-button></template></el-table-column>
        </el-table>
        <el-pagination v-model:current-page="lockPage" :total="lockTotal" :page-size="20" layout="total, prev, pager, next" @current-change="loadLocks" />
        <el-table v-if="selectedLocks.length" :data="selectedLocks"><el-table-column prop="lockNo" label="已选来源" /><el-table-column prop="skuCode" label="SKU" /><el-table-column label="本次转换数量" width="180"><template #default="{ row }"><el-input-number v-model="row.quantity" :min="1" :max="row.remainingQty" :precision="0" @change="syncLockItems" /></template></el-table-column><el-table-column label="操作" width="80"><template #default="{ row }"><el-button link type="danger" @click="removeLock(row.id)">移除</el-button></template></el-table-column></el-table>
      </section>
      <el-form v-loading="optionsBusy || customerBusy" label-position="top" class="pi-form" @submit.prevent="saveCreate">
        <div class="pi-grid"><el-form-item label="客户" required><el-select v-model="draft.customerId" :loading="customerBusy" @change="chooseCustomer" :disabled="editingRevision || fixedItems && !fromLocks" filterable placeholder="选择客户"><el-option v-for="c in options.customers" :key="c.id" :value="c.id" :label="`${c.code} · ${c.name}`" /></el-select></el-form-item><el-form-item label="所属代理商"><el-select v-model="draft.agentId" :disabled="editingRevision || fixedItems && !fromLocks" clearable filterable placeholder="可选；确认后该代理商可查看"><el-option v-for="a in options.agents" :key="a.id" :value="a.id" :label="`${a.code} · ${a.name}`" /></el-select></el-form-item><el-form-item label="来源销售订单"><el-select v-model="draft.sourceOrderId" clearable filterable :disabled="fromLocks || !draft.customerId || editingId !== null" placeholder="先选客户，再选来源订单"><el-option v-for="o in filteredOrders" :key="o.id" :value="o.id" :label="o.orderNo" /></el-select></el-form-item><el-form-item label="贸易类型" required><el-select v-model="draft.tradeType"><el-option value="DOMESTIC" label="内销" /><el-option value="EXPORT" label="外销" /></el-select></el-form-item><el-form-item label="币种" required><el-input v-model="draft.currencyCode" :disabled="editingRevision" maxlength="3" placeholder="CNY / USD / EUR" /></el-form-item><el-form-item label="计划发货日期"><el-date-picker v-model="draft.plannedShipDate" type="date" value-format="YYYY-MM-DD" /></el-form-item></div>
        <h3>商品明细</h3><el-alert v-if="editingRevision && fixedItems" title="数量和移除商品在确认修订时生效；不能少于已发数量。新增SKU须选择来源锁定，保存草稿不改变当前占用。" type="info" :closable="false" /><p class="pi-hint">首次保存自动带入SKU首张产品图；保存后的图片与单位随单据保留。</p><div class="pi-items-scroll"><table class="pi-draft-items"><thead><tr><th>SKU / 商品 / 规格</th><th>数量 / 单位</th><th>单价</th><th>行折扣</th><th>行税费</th><th>行金额</th><th>操作</th></tr></thead><tbody><tr v-for="(item, index) in draft.items" :key="item.key"><td><el-select v-model="item.skuId" @change="item.imageAttachmentId = undefined" :disabled="fixedItems" filterable :aria-label="`第${index + 1}行 SKU`" placeholder="选择 SKU"><el-option v-for="sku in options.skus" :key="sku.id" :value="sku.id" :disabled="sku.enabled === false || draft.items.some(other => other.key !== item.key && other.skuId === sku.id)" :label="`${sku.skuCode} · ${sku.productName}${sku.specification ? ' · ' + sku.specification : ''}`" /></el-select></td><td><el-input-number v-model="item.quantity" :disabled="fixedItems && !editingRevision" :aria-label="`第${index + 1}行数量`" :min="1" :max="2147483647" :precision="0" controls-position="right" /><el-input v-model="item.unit" maxlength="32" :aria-label="`第${index + 1}行单位`" /></td><td><el-input v-model="item.unitPrice" :aria-label="`第${index + 1}行单价`" inputmode="decimal" placeholder="0.00" /></td><td><el-input v-model="item.discountAmount" :aria-label="`第${index + 1}行折扣`" inputmode="decimal" /></td><td><el-input v-model="item.taxAmount" :aria-label="`第${index + 1}行税费`" inputmode="decimal" /></td><td>{{ lineTotal(item) == null ? '—' : fromCents(lineTotal(item)!) }}</td><td><el-button link type="danger" :disabled="fixedItems && !editingRevision || draft.items.length === 1" @click="removeItem(index)">移除</el-button></td></tr></tbody></table></div><el-button :disabled="fixedItems || draft.items.length >= 100" @click="draft.items.push(newItem())">添加商品行</el-button>
        <div class="pi-grid pi-amounts"><el-form-item label="全单追加折扣"><el-input v-model="draft.discountAmount" inputmode="decimal" /></el-form-item><el-form-item label="全单追加税费"><el-input v-model="draft.taxAmount" inputmode="decimal" /></el-form-item><el-form-item label="运费"><el-input v-model="draft.freightAmount" inputmode="decimal" /></el-form-item><el-form-item label="其他费用"><el-input v-model="draft.otherFeeAmount" inputmode="decimal" /></el-form-item><el-form-item label="保留库存所需收款金额" required><el-input v-model="draft.depositRequiredAmount" inputmode="decimal" placeholder="明确填写金额，无门槛填 0.00" /></el-form-item><el-form-item label="放行发货所需收款金额" required><el-input v-model="draft.shipmentRequiredAmount" inputmode="decimal" placeholder="明确填写金额，无门槛填 0.00" /></el-form-item><div class="pi-preview"><span>预计总金额 · {{ draft.currencyCode }}</span><strong>{{ preview ? fromCents(preview.total) : '—' }}</strong><small>商品金额 − 行及全单折扣 + 行及全单税费 + 运费 + 其他费用</small></div></div>
        <h3>单据资料与交易条款</h3><p class="pi-hint">填写本单据约定的双方资料、付款与交付条款，以及经业务核对的模板名称和版本。资料随单据保存。</p>
        <PiTemplates :trade-type="draft.tradeType" :can-manage="canManageTemplates" selectable @select="chooseTemplate" /><div class="pi-grid"><el-form-item v-for="[key, label] in documentLabels" :key="key" :label="label" :required="['sellerName', 'paymentTerms', 'deliveryTerms', 'templateCode', 'templateVersion'].includes(key)"><el-date-picker v-if="['issueDate', 'validUntil'].includes(key)" v-model="draft.document[key]" type="date" value-format="YYYY-MM-DD" /><el-input v-else v-model="draft.document[key]" :readonly="fixedDocumentFields.includes(key)" :type="['paymentTerms', 'deliveryTerms', 'sellerAddress', 'buyerAddress', 'shippingAddress'].includes(key) ? 'textarea' : 'text'" :rows="3" :maxlength="['paymentTerms', 'deliveryTerms'].includes(key) ? 2000 : ['sellerAddress', 'buyerAddress', 'shippingAddress'].includes(key) ? 1000 : key === 'buyerEmail' ? 254 : key === 'businessOwner' ? 150 : ['buyerContactName', 'buyerCountry', 'sellerCountry', 'transportMode', 'tradeTerms'].includes(key) ? 100 : key === 'buyerPhone' ? 64 : key === 'templateVersion' ? 32 : key === 'templateCode' ? 64 : ['sellerContact', 'buyerContact'].includes(key) ? 300 : 200" /></el-form-item></div>
        <el-form-item label="备注"><el-input v-model="draft.remark" type="textarea" maxlength="1000" show-word-limit /></el-form-item>
      </el-form><el-alert v-if="createError" :title="createError" type="error" :closable="false" show-icon />
      <template #footer><el-button :disabled="actionBusy" @click="createVisible = false">取消</el-button><el-button type="primary" :loading="actionBusy" :disabled="optionsBusy || customerBusy || customerLoadFailed || Boolean(optionsError)" @click="saveCreate">保存草稿</el-button></template>
    </el-dialog>

    <el-dialog v-model="detailVisible" :title="detail ? `${detail.piNo} · V${detail.revision}` : 'PI 详情'" width="min(1100px, 96vw)" :close-on-click-modal="false">
      <div v-loading="detailBusy" class="pi-detail"><el-alert v-if="operationError" :title="operationError" type="error" :closable="false" show-icon />
        <template v-if="detail"><div class="pi-detail-heading"><div><el-tag>{{ stateNames[detail.status] ?? detail.status }}</el-tag><strong>{{ detail.customerName }}</strong><span>{{ detail.tradeType === 'EXPORT' ? '外销' : '内销' }} · {{ detail.currencyCode }}</span></div><strong class="pi-grand-total">{{ money(detail.totalAmount) }}</strong></div>
          <div class="pi-actions">
            <PiRevisions v-if="internal" :pi="detail" :can-write="canWrite" @select="openDetail" />
            <el-button v-if="canWrite && detail.status === 'DRAFT'" :disabled="actionBusy" @click="openEdit">编辑草稿</el-button>
            <el-button v-if="canWrite && ['DRAFT', 'CONFIRMED'].includes(detail.status)" type="danger" plain :disabled="actionBusy" @click="cancelPi">取消 PI</el-button>
            <el-button v-if="canWrite && detail.status === 'DRAFT'" type="primary" :loading="actionBusy" @click="confirmPi">确认并发布</el-button>
            <el-button v-if="canPay && ['CONFIRMED', 'RELEASED'].includes(detail.status)" :disabled="actionBusy" @click="openPayment">登记收款</el-button>
            <el-button v-if="canRenewReservation" :disabled="actionBusy" type="warning" plain @click="openReservation()">核对库存并重新占用</el-button>
            <el-button v-if="canRelease && detail.status === 'CONFIRMED'" type="success" :disabled="actionBusy || !gateMet || reservationBlocked" @click="openRelease">放行发货</el-button>
            <el-button v-if="canLogistics && ['CONFIRMED', 'RELEASED'].includes(detail.status)" :disabled="actionBusy" @click="openLogistics()">登记物流</el-button>
            <el-button v-if="canExport" :loading="actionBusy" @click="download('pdf')">下载 PDF</el-button>
            <el-button v-if="canExport" :loading="actionBusy" @click="download('xlsx')">下载 Excel</el-button>
          </div>
          <el-alert v-if="detail.reservationStatus === 'EXPIRED_REQUIRES_REACQUIRE'" title="来源订单库存占用已到期" type="warning" :closable="false" show-icon>
            {{ isAgent ? '请联系业务人员核对库存并处理占用。' : '到账事实会保留，但登记收款不会自动恢复已经到期的库存占用。须重新核对可用库存并明确办理重新占用后，才能放行发货。' }}
            <span v-if="internal && !canManageReservation">请交由有订单及代理锁库维护权限的业务人员处理。</span>
          </el-alert>
          <el-alert v-if="internal && detail.reservationStatus === 'PAID_HELD'" title="收款达标保留，待履约或人工关闭" description="该占用不按原截止时间自动释放；本状态不代表已全额到账。" type="success" :closable="false" show-icon />
          <el-alert v-if="detail.reservationStatus === 'RELEASED'" title="来源订单库存占用已释放" description="当前来源占用不能继续用于发货放行，请联系业务人员处理。" type="warning" :closable="false" show-icon />
          <p v-if="internal && detail.reservationStatus === 'FULFILLED'" class="pi-hint">来源订单已履约。需要撤销出库时，请先核对订单占用及可撤销条件。<a v-if="allowed('ORDER_READ')" href="/orders">查看销售订单</a></p>
          <p class="pi-hint">库存占用：{{ reservationLabel(detail.reservationStatus, isAgent) }}</p>
          <el-descriptions :column="2" border><el-descriptions-item label="所属代理商">{{ detail.agentName || '—' }}</el-descriptions-item><el-descriptions-item label="计划发货">{{ detail.plannedShipDate || '—' }}</el-descriptions-item><el-descriptions-item label="创建时间">{{ detail.createdAt }}</el-descriptions-item><el-descriptions-item label="备注">{{ detail.remark || '—' }}</el-descriptions-item></el-descriptions>
          <el-tabs v-model="detailTab"><el-tab-pane label="商品与金额" name="items"><el-table :data="detail.items" empty-text="暂无商品"><el-table-column prop="skuCode" label="SKU" min-width="140" /><el-table-column label="图片" width="100"><template #default="scope"><PiItemImage v-if="scope.row.imageAttachmentId" :key="`${detail.id}-${scope.row.id}`" :pi-id="detail.id" :item-id="scope.row.id" /><span v-else>未记录</span></template></el-table-column><el-table-column prop="productName" label="商品" min-width="140" /><el-table-column prop="specification" label="规格" min-width="140" /><el-table-column prop="quantity" label="数量" width="80" /><el-table-column prop="unit" label="单位" width="80" /><el-table-column label="单价" min-width="110"><template #default="scope">{{ money(scope.row.unitPrice) }}</template></el-table-column><el-table-column label="折扣" min-width="100"><template #default="scope">{{ money(scope.row.discountAmount) }}</template></el-table-column><el-table-column label="税费" min-width="100"><template #default="scope">{{ money(scope.row.taxAmount) }}</template></el-table-column><el-table-column label="金额" min-width="120"><template #default="scope">{{ money(scope.row.lineTotal) }}</template></el-table-column></el-table><div class="pi-totals"><span>商品金额 <b>{{ money(detail.subtotal) }}</b></span><span>折扣合计 <b>− {{ money(detail.discountAmount) }}</b></span><span>税费合计 <b>{{ money(detail.taxAmount) }}</b></span><span>运费 <b>{{ money(detail.freightAmount) }}</b></span><span>其他费用 <b>{{ money(detail.otherFeeAmount ?? '0.00') }}</b></span><span>总金额 <b>{{ detail.currencyCode }} {{ money(detail.totalAmount) }}</b></span></div></el-tab-pane>
            <el-tab-pane v-if="detail.fulfillment?.length" label="履约订单" name="fulfillment">
              <template v-if="internal && detail.revisionSources?.length">
                <h3>本次修订新增来源</h3><p class="pi-hint">草稿保存的是待转换选择；确认修订后显示关联子单。来源截止和数量保留供追溯。</p>
                <el-table :data="detail.revisionSources"><el-table-column prop="lockNo" label="来源锁定" min-width="180" /><el-table-column prop="skuCode" label="SKU" min-width="120" /><el-table-column prop="quantity" label="转换数量" width="100" /><el-table-column prop="expiresAt" label="来源截止" min-width="180" /><el-table-column label="关联子单" min-width="180"><template #default="{ row }">{{ row.orderId ? detail.fulfillment.find(line => line.orderId === row.orderId)?.orderNo || row.orderId : '待确认转换' }}</template></el-table-column></el-table>
              </template>
              <el-table :data="detail.fulfillment"><el-table-column prop="orderNo" label="订单" min-width="200" /><el-table-column prop="skuCode" label="SKU" min-width="120" /><el-table-column prop="orderedQty" label="约定" width="70" /><el-table-column prop="shippedQty" label="已发" width="70" /><el-table-column prop="closedQty" label="关闭" width="70" /><el-table-column prop="remainingQty" label="待发" width="70" /><el-table-column label="库存占用" min-width="180"><template #default="{ row }">{{ reservationLabel(row.reservationStatus, isAgent) }}</template></el-table-column><el-table-column v-if="canManageReservation" label="操作" width="130"><template #default="{ row }"><el-button v-if="['TIMED_ACTIVE', 'EXPIRED_REQUIRES_REACQUIRE'].includes(row.reservationStatus)" link :disabled="actionBusy" @click="openReservation(row.orderId)">核对并重新占用</el-button></template></el-table-column></el-table>
            </el-tab-pane>
            <el-tab-pane label="真实出库批次" name="batches"><el-table :data="detail.dispatchBatches || []" empty-text="尚无实际出库批次"><el-table-column prop="batchNo" label="出库批次" min-width="240" /><el-table-column prop="createdAt" label="出库时间" min-width="180" /><el-table-column prop="originalQty" label="原出库" width="85" /><el-table-column prop="activeQty" label="有效数量" width="90" /><el-table-column label="来源" min-width="150"><template #default="{ row }">{{ row.sourceType === 'LEGACY_SINGLE' ? '历史单车记录（非原合批）' : row.sourceType === 'PI_COMMAND' ? 'PI整批出库' : '订单批量出库' }}</template></el-table-column><el-table-column label="状态" width="100"><template #default="{ row }">{{ row.status === 'COMPLETED' ? '已出库' : row.status === 'CANCELLED' ? '已撤销' : '部分撤销' }}</template></el-table-column><el-table-column label="车辆" width="90"><template #default="{ row }"><el-button link @click="openBatch(row.id)">查看</el-button></template></el-table-column></el-table></el-tab-pane>
            <el-tab-pane label="单据资料与条款" name="document"><el-descriptions :column="2" border><el-descriptions-item v-for="[key, label] in documentLabels" :key="key" :label="label"><span class="pi-prewrap">{{ detail.document?.[key] || '—' }}</span></el-descriptions-item></el-descriptions></el-tab-pane>
            <el-tab-pane v-if="showFinance" label="收款与放行" name="payment"><el-descriptions :column="2" border><el-descriptions-item label="累计到账">{{ detail.currencyCode }} {{ money(detail.paidAmount) }}</el-descriptions-item><el-descriptions-item label="收款状态">{{ paymentNames[detail.paymentStatus ?? ''] ?? detail.paymentStatus ?? '—' }}</el-descriptions-item><el-descriptions-item label="保留库存门槛">{{ money(detail.depositRequiredAmount) }}</el-descriptions-item><el-descriptions-item label="发货放行门槛">{{ money(detail.shipmentRequiredAmount) }}</el-descriptions-item><el-descriptions-item label="放行状态">{{ detail.releaseStatus === 'RELEASED' ? '已放行' : '未放行' }}</el-descriptions-item><el-descriptions-item label="放行时间">{{ detail.releasedAt || '—' }}</el-descriptions-item></el-descriptions><p v-if="canRelease && detail.status === 'CONFIRMED' && !gateMet" class="pi-hint">累计到账尚未达到发货门槛，暂不能放行。</p><el-table :data="detail.payments" empty-text="尚无到账记录"><el-table-column label="状态" width="100"><template #default="scope">{{ scope.row.status === 'REVERSED' ? '已撤回登记' : '已确认' }}</template></el-table-column><el-table-column label="收款方式" min-width="140"><template #default="scope">{{ paymentMethods[scope.row.paymentMethod] || '未记录' }} {{ scope.row.paymentMethodNote }}</template></el-table-column><el-table-column prop="recordedAt" label="登记时间" min-width="170" /><el-table-column prop="confirmedBy" label="登记人ID" width="100" /><el-table-column prop="reversalReason" label="撤回原因" min-width="180" /><el-table-column prop="reversedAt" label="撤回时间" min-width="170" /><el-table-column prop="reversedBy" label="撤回人ID" width="100" /><el-table-column v-if="canReversePayment" label="更正" width="110"><template #default="scope"><el-button v-if="scope.row.status === 'CONFIRMED'" link type="danger" @click="openPaymentReversal(scope.row)">撤回错误登记</el-button></template></el-table-column><el-table-column prop="receivedAt" label="到账时间" min-width="170" /><el-table-column label="金额" min-width="130"><template #default="scope">{{ scope.row.currencyCode }} {{ money(scope.row.amount) }}</template></el-table-column><el-table-column prop="remark" label="说明" min-width="220" /><el-table-column label="收款凭证" width="120"><template #default="scope"><el-button link type="primary" @click="openReceipt(scope.row)">{{ canPay ? '查看 / 上传' : '查看' }}</el-button></template></el-table-column></el-table></el-tab-pane>
            <el-tab-pane label="物流信息" name="logistics"><el-table :data="detail.logistics" empty-text="尚无物流记录"><el-table-column label="附件" width="90"><template #default="{ row }"><el-button link @click="openLogisticsAttachment(row)">查看附件</el-button></template></el-table-column><el-table-column label="来源依据" min-width="130"><template #default="{ row }">{{ row.linkType === 'ACTUAL_BATCH' ? '真实出库批次' : row.linkType === 'LEGACY_UNLINKED' ? '历史手工记录，未关联' : '未关联出库的计划' }}</template></el-table-column><el-table-column prop="shippedQtySnapshot" label="确认发运台数" width="120" /><el-table-column prop="batchNo" label="物流批次" min-width="150" /><el-table-column prop="carrier" label="承运商" min-width="130" /><el-table-column prop="trackingNo" label="运单号" min-width="170" /><el-table-column label="状态" width="100"><template #default="scope">{{ logisticsStates[scope.row.status] || scope.row.status }}</template></el-table-column><el-table-column prop="shippedAt" label="发货时间" min-width="180" /><el-table-column prop="remark" label="备注" min-width="180" /><el-table-column v-if="internal" label="操作" width="210"><template #default="{ row }"><el-button v-if="canLogistics && row.status !== 'SHIPPED'" link :disabled="actionBusy" @click="openLogistics(row)">编辑 / 确认发运</el-button><el-button v-if="canLogistics && row.status !== 'WITHDRAWN'" link :disabled="actionBusy" @click="withdrawLogistics(row)">撤回</el-button><el-button link @click="openLogisticsHistory(row)">历史</el-button></template></el-table-column></el-table></el-tab-pane>
            <el-tab-pane v-if="canExport" label="导出任务" name="exports"><PiExportTask v-if="detailTab === 'exports'" :pi-id="detail.id" :can-retry="internal && canExport" :status="detail.status" /></el-tab-pane>
            <el-tab-pane label="确认附件" name="confirmations"><PiConfirmationAttachments v-if="detailTab === 'confirmations'" :pi-id="detail.id" :can-upload="canWrite && ['DRAFT', 'CONFIRMED', 'RELEASED'].includes(detail.status)" /></el-tab-pane>
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
    <el-dialog v-model="batchVisible" title="出库批次车辆" width="min(1000px, 94vw)"><el-alert v-if="batchError" :title="batchError" type="error" :closable="false" /><el-table v-loading="batchBusy" :data="batchVehicles"><el-table-column prop="vehicleQrCode" label="聚合码" min-width="240" show-overflow-tooltip /><el-table-column prop="frameSn" label="车架码" min-width="180" /><el-table-column prop="skuCode" label="SKU" /><el-table-column prop="orderNo" label="履约订单" min-width="180" /><el-table-column prop="stockOutNo" label="出库单" min-width="180" /><el-table-column label="状态"><template #default="{ row }">{{ row.status === 'COMPLETED' ? '已出库' : '已撤销' }}</template></el-table-column><el-table-column prop="cancelReason" label="撤销原因" /></el-table></el-dialog>
    <el-dialog v-model="reservationVisible" title="核对库存并重新占用" width="540px" :close-on-click-modal="false" :close-on-press-escape="!actionBusy">
      <p>提交时重新检查可用库存。已经到期的占用不会因登记收款而自动恢复；库存不足时本次操作会被拒绝。</p>
      <p class="pi-hint">如关联的已确认 PI 已达到保留库存门槛，成功占用后将按收款达标规则保留，待履约或人工关闭。</p>
      <el-form label-position="top" @submit.prevent="saveReservation"><el-form-item label="未达保留门槛时的占用时长（小时）" required><el-input-number v-model="reservation.hours" :min="1" :max="720" :precision="0" /></el-form-item><el-form-item label="重新占用原因" required><el-input v-model="reservation.reason" type="textarea" maxlength="500" show-word-limit /></el-form-item></el-form>
      <el-alert v-if="reservationError" :title="reservationError" type="error" :closable="false" show-icon />
      <template #footer><el-button :disabled="actionBusy" @click="reservationVisible = false">取消</el-button><el-button type="primary" :loading="actionBusy" @click="saveReservation">确认重新占用</el-button></template>
    </el-dialog>
    <el-dialog v-model="paymentVisible" title="登记到账" width="520px" :close-on-click-modal="false"><el-form label-position="top"><el-form-item label="到账金额" required><el-input v-model="payment.amount" inputmode="decimal"><template #append>{{ payment.currencyCode }}</template></el-input></el-form-item><el-form-item label="到账时间"><el-date-picker v-model="payment.receivedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="留空使用当前时间" /></el-form-item><el-form-item label="收款方式" required><el-select v-model="payment.paymentMethod"><el-option v-for="(label, key) in paymentMethods" :key="key" :value="key" :label="label" /></el-select></el-form-item><el-form-item v-if="payment.paymentMethod === 'OTHER'" label="其他方式说明" required><el-input v-model="payment.paymentMethodNote" maxlength="200" /></el-form-item><el-form-item label="到账说明"><el-input v-model="payment.remark" type="textarea" maxlength="500" placeholder="到账来源、银行流水说明等" /></el-form-item></el-form><el-alert v-if="paymentError" :title="paymentError" type="error" :closable="false" /><template #footer><el-button :disabled="actionBusy" @click="paymentVisible = false">取消</el-button><el-button type="primary" :loading="actionBusy" @click="savePayment">确认到账</el-button></template></el-dialog>
    <el-dialog v-model="releaseVisible" title="放行发货" width="520px" :close-on-click-modal="false"><p>请核对本单据的收款与发货条件，放行后可继续办理关联订单出库。</p><el-form label-position="top"><el-form-item label="放行说明" required><el-input v-model="releaseReason" type="textarea" maxlength="500" /></el-form-item></el-form><el-alert v-if="releaseError" :title="releaseError" type="error" :closable="false" /><template #footer><el-button :disabled="actionBusy" @click="releaseVisible = false">取消</el-button><el-button type="success" :loading="actionBusy" @click="saveRelease">确认放行</el-button></template></el-dialog>
    <el-dialog v-model="logisticsVisible" title="登记物流批次" width="560px" :close-on-click-modal="false"><el-form label-position="top"><el-form-item label="真实出库批次"><el-select v-model="logistics.stockOutBatchId" clearable placeholder="确认发运时必选"><el-option v-for="batch in detail?.dispatchBatches || []" :key="batch.id" :value="batch.id" :disabled="batch.activeQty === 0" :label="`${batch.batchNo} · 有效 ${batch.activeQty} 台`" /></el-select></el-form-item><el-form-item label="物流批次 / 计划编号" required><el-input v-model="logistics.batchNo" :disabled="Boolean(logistics.stockOutBatchId)" maxlength="64" /></el-form-item><el-form-item label="物流状态" required><el-select v-model="logistics.status"><el-option value="PLANNED" label="计划中" /><el-option value="SHIPPED" label="已发货" :disabled="detail?.status !== 'RELEASED'" /></el-select></el-form-item><el-form-item label="承运商" :required="logistics.status === 'SHIPPED'"><el-input v-model="logistics.carrier" maxlength="128" /></el-form-item><el-form-item label="运单号" :required="logistics.status === 'SHIPPED'"><el-input v-model="logistics.trackingNo" maxlength="128" /></el-form-item><el-form-item label="备注"><el-input v-model="logistics.remark" type="textarea" maxlength="500" /></el-form-item></el-form><el-alert v-if="logisticsError" :title="logisticsError" type="error" :closable="false" /><template #footer><el-button :disabled="actionBusy" @click="logisticsVisible = false">取消</el-button><el-button type="primary" :loading="actionBusy" @click="saveLogistics">保存物流</el-button></template></el-dialog>
  </section>
</template>

<style scoped>
.pi-workspace{padding:28px 32px;min-width:0;max-width:1500px;margin:auto}.pi-heading{display:flex;justify-content:space-between;gap:20px;align-items:center;margin-bottom:24px}.pi-heading h2{margin:0 0 7px;font-size:24px}.pi-heading p,.pi-hint{color:#65758a;font-size:13px;line-height:1.7}.pi-heading p{margin:0}.pi-filters{display:flex;gap:12px;align-items:center;margin-bottom:18px}.pi-filters>.el-input{max-width:340px}.pi-filters>.el-select{width:160px}.el-pagination{margin-top:20px;justify-content:flex-end;flex-wrap:wrap}.el-alert{margin:12px 0}.pi-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:0 18px}.pi-form .el-select,.pi-form .el-date-editor,.el-form-item .el-date-editor{width:100%}.pi-form h3{font-size:16px;margin:22px 0 14px}.pi-items-scroll{overflow-x:auto;margin-bottom:12px}.pi-draft-items{min-width:1020px}.pi-draft-items th,.pi-draft-items td{padding:10px 6px}.pi-draft-items th:first-child{width:340px}.pi-draft-items .el-input-number{width:108px}.pi-draft-items .el-input{min-width:98px}.pi-draft-items .el-select{min-width:320px}.pi-amounts{margin-top:24px}.pi-preview{background:#f1f7fc;border-radius:8px;padding:14px 18px;display:flex;flex-direction:column;gap:7px;align-self:start}.pi-preview span{font-size:12px;color:#52687d}.pi-preview strong{font-size:23px;color:#1677c8}.pi-preview small{font-size:11px;line-height:1.6;color:#748196}.pi-detail{min-height:140px}.pi-detail-heading{display:flex;justify-content:space-between;align-items:center;gap:20px;margin:8px 0 18px}.pi-detail-heading>div{display:flex;gap:12px;flex-wrap:wrap;align-items:center}.pi-detail-heading span{font-size:13px;color:#65758a}.pi-grand-total{font-size:24px;color:#1677c8}.pi-actions{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:18px}.pi-actions .el-button+.el-button{margin-left:0}.pi-totals{display:flex;justify-content:flex-end;gap:22px;flex-wrap:wrap;background:#f6f9fc;padding:18px;margin-top:16px}.pi-totals span{display:flex;flex-direction:column;gap:8px;color:#65758a;font-size:12px}.pi-totals b{font-size:15px;color:#23364d}.pi-prewrap{white-space:pre-wrap;overflow-wrap:anywhere}.el-tabs{margin-top:18px}@media(max-width:900px){.pi-workspace{padding:22px 18px}.pi-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.pi-filters{flex-wrap:wrap}}@media(max-width:580px){.pi-grid{grid-template-columns:1fr}.pi-heading{align-items:flex-start;flex-direction:column}.pi-detail-heading{align-items:flex-start;flex-direction:column}.pi-filters>.el-input{max-width:none;width:100%}.pi-grand-total{font-size:21px}}
</style>
