<script setup lang="ts">
import TraceDetails, { type TraceData } from './components/TraceDetails.vue'
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import axios from './api'
import { showFailToast, showSuccessToast } from 'vant'
import { hasPermission, isSuperAdmin } from './permissions'
import ProductionStation from './components/ProductionStation.vue'
import PiDispatchPanel from './components/PiDispatchPanel.vue'

const username = ref('admin')
const password = ref('')
const loading = ref(false)
const loggedIn = ref(Boolean(localStorage.getItem('bike_access_token')))
const dispatchMode = ref('order')
const pathMode: Record<string, 'assembly' | 'stockIn' | 'stockOut' | 'rework' | 'trace'> = {
  '/pda/home': 'assembly', '/pda/assembly': 'assembly', '/pda/stock-in': 'stockIn', '/pda/stock-out': 'stockOut', '/pda/rework': 'rework', '/pda/trace': 'trace'
}
const mode = ref<'assembly' | 'stockIn' | 'stockOut' | 'rework' | 'trace'>(pathMode[window.location.pathname] ?? 'assembly')
type PdaMode = 'assembly' | 'stockIn' | 'stockOut' | 'rework' | 'trace'
type AuthContext = { username: string; userType: string; agentId: number | null; roles: string[]; permissions: string[]; workstations: string[]; mustChangePassword: boolean }
const authContext = ref<AuthContext | null>(null)
const passwordChangeVisible = ref(false)
const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const passwordChanging = ref(false)
const allModes: PdaMode[] = ['assembly', 'stockIn', 'stockOut', 'rework', 'trace']
const allowedModes = computed<PdaMode[]>(() => {
  const context = authContext.value
  if (!context || isSuperAdmin(context) || context.workstations.includes('*')) return allModes
  const modeByWorkstation: Record<string, PdaMode> = { ASSEMBLY: 'assembly', FRAME: 'assembly', MOTOR: 'assembly', BATTERY: 'assembly', DISPLAY: 'assembly', STOCK_IN: 'stockIn', STOCK_OUT: 'stockOut', REWORK: 'rework' }
  const modes = context.workstations.map(workstation => modeByWorkstation[workstation]).filter((value): value is PdaMode => Boolean(value))
  if (hasPermission(context, 'TRACE_READ') && modes.length > 0) modes.push('trace')
  return [...new Set(modes)]
})
type ScanFieldRef = { focus: () => void }
const vehicleQrField = ref<ScanFieldRef | null>(null)
const reworkQrField = ref<ScanFieldRef | null>(null)
const reworkPartField = ref<ScanFieldRef | null>(null)
const reworkReasonField = ref<ScanFieldRef | null>(null)
const traceField = ref<ScanFieldRef | null>(null)
const vehicleQrCode = ref('')
const scannedVehicleQrs = ref<string[]>([])
const customerId = ref<number | null>(null)
const orderId = ref<number | null>(null)
const warehouseId = ref<number | null>(null)
const locationId = ref<number | null>(null)
const customers = ref<Array<{ id: number; name: string; code: string }>>([])
type StockOutOrder = { id: number; orderNo: string; customerId: number; modelName: string; orderQty: number; shippedQty: number; status: string }
const orders = ref<StockOutOrder[]>([]), selectedOrder = ref<StockOutOrder | null>(null)
const orderKeyword = ref(''), appliedOrderKeyword = ref(''), orderPage = ref(1), orderTotal = ref(0)
const orderPageSize = 20, orderOptionsLoading = ref(false), orderOptionsError = ref('')
let orderRequestVersion = 0, customerRequestVersion = 0
const orderPageCount = computed(() => Math.max(1, Math.ceil(orderTotal.value / orderPageSize)))
const warehouses = ref<Array<{ id: number; name: string; code: string; status: string }>>([])
const locations = ref<Array<{ id: number; name: string; code: string; status: string }>>([])
const traceKey = ref('')
const traceType = ref<'frame' | 'part' | 'qr'>('qr')
const traceData = ref<TraceData | null>(null)
const reworkQrCode = ref('')
const reworkPartType = ref('BATTERY')
const reworkNewPartSn = ref('')
const reworkReason = ref('')
const resultMessage = ref('')
const resultSuccess = ref(false)
const busy = ref(false)
// 每次请求读取当前凭证，避免重新登录后继续使用 computed 缓存的旧 token。
const tokenHeaders = { get value() { return { Authorization: `Bearer ${localStorage.getItem('bike_access_token')}` } } }
const customerOptions = computed(() => customers.value.map(customer => ({ text: `${customer.name} (${customer.code})`, value: customer.id })))
const orderOptions = computed(() => {
  const selected = selectedOrder.value
  const options = selected && selected.customerId === customerId.value && !orders.value.some(order => order.id === selected.id)
    ? [selected, ...orders.value] : orders.value
  return options
  .filter(order => order.customerId === customerId.value && order.orderQty > order.shippedQty)
  .map(order => ({
    text: `${order.orderNo} · ${order.modelName} · 剩余 ${Math.max(0, order.orderQty - order.shippedQty - (order.id === orderId.value ? scannedVehicleQrs.value.length : 0))} 台`,
    value: order.id,
  }))
})
const orderTotalQty = computed(() => selectedOrder.value?.orderQty ?? 0)
const orderShippedQty = computed(() => selectedOrder.value?.shippedQty ?? 0)
const orderRemainingQty = computed(() => Math.max(0, orderTotalQty.value - orderShippedQty.value - scannedVehicleQrs.value.length))
const warehouseOptions = computed(() => warehouses.value.filter(warehouse => warehouse.status === 'ENABLED').map(warehouse => ({ text: `${warehouse.name} (${warehouse.code})`, value: warehouse.id })))
const locationOptions = computed(() => locations.value.filter(location => location.status === 'ENABLED').map(location => ({ text: `${location.name} (${location.code})`, value: location.id })))

async function loadAuthContext() {
  const response = await axios.get('/api/auth/context', { headers: tokenHeaders.value })
  authContext.value = response.data.data
}

async function loadStockOutOptions() {
  const version = ++customerRequestVersion
  try {
    const customerResponse = await axios.get('/api/master/customers', { headers: tokenHeaders.value })
    if (version !== customerRequestVersion || !loggedIn.value) return
    customers.value = customerResponse.data.data.filter((customer: { status: string }) => customer.status === 'ENABLED')
    if (customerId.value === null || !customers.value.some(customer => customer.id === customerId.value)) {
      customerId.value = customers.value[0]?.id ?? null
    } else await loadStockOutOrders(1)
  } catch (error) {
    if (version === customerRequestVersion) showFailToast(axios.isAxiosError(error) ? error.response?.data?.message ?? '客户加载失败' : '客户加载失败')
  }
}

async function loadStockOutOrders(page: number) {
  const version = ++orderRequestVersion, customer = customerId.value
  if (customer === null || !loggedIn.value || mode.value !== 'stockOut') { orders.value = []; orderTotal.value = 0; orderOptionsLoading.value = false; return }
  orderOptionsLoading.value = true; orderOptionsError.value = ''
  try {
    const response = await axios.get('/api/orders/stock-out-options', {
      headers: tokenHeaders.value,
      params: { customerId: customer, keyword: appliedOrderKeyword.value || undefined, page, pageSize: orderPageSize },
    })
    if (version !== orderRequestVersion || customer !== customerId.value || !loggedIn.value) return
    orders.value = response.data.data.records; orderTotal.value = response.data.data.total; orderPage.value = response.data.data.page
    const refreshed = orders.value.find(order => order.id === orderId.value)
    if (refreshed) selectedOrder.value = refreshed
  } catch (error) {
    if (version === orderRequestVersion) {
      orders.value = []; orderTotal.value = 0
      orderOptionsError.value = axios.isAxiosError(error) ? error.response?.data?.message ?? '订单加载失败，请重试' : '订单加载失败，请重试'
    }
  } finally { if (version === orderRequestVersion) orderOptionsLoading.value = false }
}

function searchStockOutOrders() {
  appliedOrderKeyword.value = orderKeyword.value.trim()
  void loadStockOutOrders(1)
}

async function loadStockInOptions() {
  try {
    const response = await axios.get('/api/master/warehouses', { headers: tokenHeaders.value })
    warehouses.value = response.data.data
    if (warehouseId.value === null && warehouseOptions.value.length > 0) warehouseId.value = warehouseOptions.value[0].value
  } catch (error) {
    showFailToast(axios.isAxiosError(error) ? error.response?.data?.message ?? '仓库加载失败' : '仓库加载失败')
  }
}

async function loadOptionsForMode(nextMode: PdaMode) {
  if (nextMode === 'stockOut') await loadStockOutOptions()
  if (nextMode === 'stockIn') await loadStockInOptions()
}

async function submitPasswordChange() {
  if (newPassword.value.length < 8 || newPassword.value.length > 100) {
    showFailToast('新密码长度必须为 8-100')
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    showFailToast('两次输入的新密码不一致')
    return
  }
  passwordChanging.value = true
  try {
    await axios.put('/api/auth/password', { currentPassword: currentPassword.value, newPassword: newPassword.value }, { headers: tokenHeaders.value })
    currentPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
    passwordChangeVisible.value = false
    clearSession()
    showSuccessToast('密码已更新，请使用新密码重新登录')
  } catch (error) {
    showFailToast(axios.isAxiosError(error) ? error.response?.data?.message ?? '密码修改失败' : '密码修改失败')
  } finally {
    passwordChanging.value = false
  }
}

async function loadLocations() {
  locations.value = []
  locationId.value = null
  if (warehouseId.value === null) return
  try {
    const response = await axios.get(`/api/master/warehouses/${warehouseId.value}/locations`, { headers: tokenHeaders.value })
    locations.value = response.data.data
    if (locationOptions.value.length > 0) locationId.value = locationOptions.value[0].value
  } catch (error) {
    showFailToast(axios.isAxiosError(error) ? error.response?.data?.message ?? '库位加载失败' : '库位加载失败')
  }
}

async function login() {
  loading.value = true
  try {
    const response = await axios.post('/api/auth/login', { username: username.value, password: password.value })
    localStorage.setItem('bike_access_token', response.data.data.accessToken)
    loggedIn.value = true
    await loadAuthContext()
    if (authContext.value?.mustChangePassword) {
      passwordChangeVisible.value = true
      return
    }
    if (!allowedModes.value.includes(mode.value)) mode.value = allowedModes.value[0] ?? 'assembly'
    await loadOptionsForMode(mode.value)
    showSuccessToast('登录成功')
  } catch (error) {
    const message = axios.isAxiosError(error) ? error.response?.data?.message ?? '登录失败' : '登录失败'
    showFailToast(message)
  } finally {
    loading.value = false
  }
}

async function submitStockIn() {
  if (warehouseId.value === null || locationId.value === null) {
    resultMessage.value = '请选择仓库和库位'
    showFailToast(resultMessage.value)
    return
  }
  busy.value = true
  try {
    const response = await axios.post('/api/stock-in', { vehicleQrCode: vehicleQrCode.value, warehouseId: warehouseId.value, locationId: locationId.value }, { headers: tokenHeaders.value })
    resultMessage.value = `入库完成：${vehicleQrCode.value}`
    resultSuccess.value = true
    vehicleQrCode.value = ''
    focusField(vehicleQrField)
    showSuccessToast('入库完成')
  } catch (error) { resultMessage.value = axios.isAxiosError(error) ? error.response?.data?.message ?? '入库失败' : '入库失败'; resultSuccess.value = false; showFailToast(resultMessage.value) } finally { busy.value = false }
}

async function submitStockOut() {
  if (customerId.value === null || orderId.value === null) {
    resultMessage.value = '请选择客户和可出库订单'
    showFailToast(resultMessage.value)
    return
  }
  busy.value = true
  try {
    if (scannedVehicleQrs.value.length === 0) {
      resultMessage.value = '请至少扫描一辆整车'
      showFailToast(resultMessage.value)
      return
    }
    const response = await axios.post('/api/stock-out', { customerId: customerId.value, orderId: orderId.value, vehicleQrCodes: scannedVehicleQrs.value }, { headers: tokenHeaders.value })
    resultMessage.value = `出库完成：${scannedVehicleQrs.value.length} 台整车`
    resultSuccess.value = true
    scannedVehicleQrs.value = []
    vehicleQrCode.value = ''
    orderId.value = null; selectedOrder.value = null
    await loadStockOutOrders(1)
    focusField(vehicleQrField)
    showSuccessToast('出库完成')
  } catch (error) { resultMessage.value = axios.isAxiosError(error) ? error.response?.data?.message ?? '出库失败' : '出库失败'; resultSuccess.value = false; showFailToast(resultMessage.value) } finally { busy.value = false }
}

async function scanStockOutVehicle() {
  if (customerId.value === null || orderId.value === null) {
    resultMessage.value = '请先选择客户和可出库订单'
    resultSuccess.value = false
    showFailToast(resultMessage.value)
    return
  }
  if (orderRemainingQty.value <= 0) {
    resultMessage.value = '订单剩余数量不足，不能继续扫码'
    resultSuccess.value = false
    showFailToast(resultMessage.value)
    return
  }
  const qr = vehicleQrCode.value.trim()
  if (!qr) return
  if (scannedVehicleQrs.value.includes(qr)) {
    resultMessage.value = '同一整车不能重复扫码'
    resultSuccess.value = false
    showFailToast(resultMessage.value)
    vehicleQrCode.value = ''
    return
  }
  try {
    const response = await axios.post('/api/stock-out/validate', {
      vehicleQrCode: qr,
      customerId: customerId.value,
      orderId: orderId.value,
    }, { headers: tokenHeaders.value })
    if (!response.data.data.valid) throw new Error(response.data.data.message)
    scannedVehicleQrs.value.push(qr)
    resultMessage.value = `已扫描 ${scannedVehicleQrs.value.length} 台，订单剩余 ${orderRemainingQty.value} 台`
    resultSuccess.value = true
    vehicleQrCode.value = ''
    focusField(vehicleQrField)
  } catch (error) {
    resultMessage.value = error instanceof Error && !(axios.isAxiosError(error)) ? error.message : axios.isAxiosError(error) ? error.response?.data?.message ?? '二维码校验失败' : '二维码校验失败'
    resultSuccess.value = false
    showFailToast(resultMessage.value)
  }
}

async function submitRework() {
  if (!reworkQrCode.value.trim() || !reworkNewPartSn.value.trim() || !reworkReason.value.trim()) {
    resultMessage.value = '请填写聚合码、新零部件和返工原因'
    showFailToast(resultMessage.value)
    return
  }
  busy.value = true
  try {
    const response = await axios.post('/api/rework', { vehicleQrCode: reworkQrCode.value, partType: reworkPartType.value, newPartSn: reworkNewPartSn.value, reason: reworkReason.value }, { headers: tokenHeaders.value })
    resultMessage.value = `返工完成：${response.data.data.reworkNo}，新部件 ${response.data.data.newPartSn}`
    resultSuccess.value = true
    reworkQrCode.value = ''
    reworkNewPartSn.value = ''
    reworkReason.value = ''
    focusField(reworkQrField)
    showSuccessToast('返工完成')
  } catch (error) {
    resultMessage.value = axios.isAxiosError(error) ? error.response?.data?.message ?? '返工失败' : '返工失败'
    resultSuccess.value = false
    showFailToast(resultMessage.value)
  } finally { busy.value = false }
}

async function queryTrace() {
  busy.value = true
  traceData.value = null
  try {
    if (!traceKey.value.trim()) throw new Error('请输入追溯标识')
    const response = await axios.get(`/api/trace/${traceType.value}/${encodeURIComponent(traceKey.value.trim())}`, { headers: tokenHeaders.value })
    const vehicle = response.data.data.vehicle
    traceData.value = response.data.data
    resultMessage.value = `${vehicle.vehicleQrCode || '尚未生成聚合码'} · ${vehicle.statusName} · ${vehicle.modelName ?? '-'} · 绑定历史 ${response.data.data.bindingHistory.length} 条`
    resultSuccess.value = true
    showSuccessToast('追溯查询完成')
  } catch (error) { resultMessage.value = axios.isAxiosError(error) ? error.response?.data?.message ?? '追溯失败' : error instanceof Error ? error.message : '追溯失败'; resultSuccess.value = false; showFailToast(resultMessage.value) } finally { busy.value = false; focusField(traceField) }
}

async function traceRelatedCode(code: string, codeType: 'qr' | 'frame') {
  traceType.value = codeType
  traceKey.value = code
  await queryTrace()
}

async function validateStockOperation(stockType: 'in' | 'out') {
  if (!vehicleQrCode.value.trim()) return
  try {
    const payload = stockType === 'out'
      ? { vehicleQrCode: vehicleQrCode.value, customerId: customerId.value, orderId: orderId.value }
      : { vehicleQrCode: vehicleQrCode.value }
    const response = await axios.post(`/api/stock-${stockType}/validate`, payload, { headers: tokenHeaders.value })
    resultMessage.value = response.data.data.message
    resultSuccess.value = response.data.data.valid
  } catch (error) { resultMessage.value = axios.isAxiosError(error) ? error.response?.data?.message ?? '二维码校验失败' : '二维码校验失败'; resultSuccess.value = false }
}

function clearSession() {
  if(authContext.value?.username) sessionStorage.removeItem(`bike_pi_dispatch:${authContext.value.username}`)
  dispatchMode.value = 'order'
  localStorage.removeItem('bike_access_token'); loggedIn.value = false; passwordChangeVisible.value = false
  authContext.value = null; password.value = ''; scannedVehicleQrs.value = []; traceData.value = null
  ++orderRequestVersion; ++customerRequestVersion
  customerId.value = null; orderId.value = null; selectedOrder.value = null; customers.value = []; orders.value = []
  orderTotal.value = 0; orderOptionsLoading.value = false; orderOptionsError.value = ''
  resultMessage.value = ''; resultSuccess.value = false; vehicleQrCode.value = ''; traceKey.value = ''
  reworkQrCode.value = ''; reworkNewPartSn.value = ''; reworkReason.value = ''
  warehouseId.value = null; locationId.value = null; warehouses.value = []; locations.value = []
}
async function logout() {
  try {
    if (localStorage.getItem('bike_access_token')) await axios.post('/api/auth/logout', {}, { headers: tokenHeaders.value })
    clearSession()
  } catch (error) {
    if (axios.isAxiosError(error) && error.response?.status === 401) clearSession()
    else showFailToast('退出未完成，请检查网络后重试')
  }
}

function handleAuthExpired() {
  clearSession()
}

function focusField(field: { value: ScanFieldRef | null } | ScanFieldRef | null) {
  void nextTick(() => {
    const target = field && 'value' in field ? field.value : field
    target?.focus()
  })
}

watch(allowedModes, (nextModes) => {
  if (nextModes.length > 0 && !nextModes.includes(mode.value)) mode.value = nextModes[0]
})

watch(mode, (nextMode) => {
  if (loggedIn.value && !passwordChangeVisible.value) {
    void loadOptionsForMode(nextMode)
    if (nextMode === 'stockIn' || nextMode === 'stockOut') focusField(vehicleQrField)
    if (nextMode === 'rework') focusField(reworkQrField)
    if (nextMode === 'trace') focusField(traceField)
  }
})

watch(customerId, () => {
  orderId.value = null; selectedOrder.value = null; orders.value = []; orderTotal.value = 0
  orderKeyword.value = ''; appliedOrderKeyword.value = ''; orderPage.value = 1
  scannedVehicleQrs.value = []
  vehicleQrCode.value = ''
  void loadStockOutOrders(1)
})

watch(orderId, (nextOrderId, previousOrderId) => {
  if (nextOrderId !== previousOrderId) {
    selectedOrder.value = orders.value.find(order => order.id === nextOrderId) ?? (selectedOrder.value?.id === nextOrderId ? selectedOrder.value : null)
    scannedVehicleQrs.value = []
    vehicleQrCode.value = ''
  }
})

watch(warehouseId, () => { void loadLocations() })

onMounted(() => {
  window.addEventListener('bike-auth-expired', handleAuthExpired)
})

onUnmounted(() => {
  window.removeEventListener('bike-auth-expired', handleAuthExpired)
})

if (loggedIn.value) {
  void loadAuthContext().then(() => {
    if (authContext.value?.mustChangePassword) {
      passwordChangeVisible.value = true
      return
    }
    if (!allowedModes.value.includes(mode.value)) mode.value = allowedModes.value[0] ?? 'assembly'
    return loadOptionsForMode(mode.value)
  }).catch(() => {
    logout()
    showFailToast('登录状态已失效，请重新登录')
  })
}
</script>

<template>
  <main class="pda-shell">
    <section v-if="!loggedIn" class="login-panel">
      <p class="eyebrow">BIKE PDA</p>
      <h1>工位登录</h1>
      <p class="description">生产扫码作业端</p>
      <van-form @submit="login">
        <van-cell-group inset>
          <van-field v-model="username" name="username" label="账号" placeholder="请输入账号" />
          <van-field v-model="password" name="password" type="password" autocomplete="current-password" label="密码" placeholder="请输入密码" />
        </van-cell-group>
        <div class="action">
          <van-button block type="primary" native-type="submit" :loading="loading">登录</van-button>
        </div>
      </van-form>
    </section>
    <section v-else class="work-panel">
      <header class="work-header"><div><p class="eyebrow">BIKE PDA</p><h1>工位作业</h1></div><van-button size="small" plain type="primary" @click="logout">退出</van-button></header>
      <van-dialog v-model:show="passwordChangeVisible" title="首次登录请修改密码" :show-confirm-button="false" :close-on-click-overlay="false">
        <div class="password-dialog-form">
          <van-field v-model="currentPassword" type="password" label="当前密码" />
          <van-field v-model="newPassword" type="password" label="新密码" />
          <van-field v-model="confirmPassword" type="password" label="确认新密码" />
          <van-button block type="primary" :loading="passwordChanging" @click="submitPasswordChange">更新密码</van-button>
        </div>
      </van-dialog>
      <van-tabs v-if="allowedModes.length > 0" v-model:active="mode" shrink>
        <van-tab v-if="allowedModes.includes('assembly')" name="assembly" title="组装" />
        <van-tab v-if="allowedModes.includes('stockIn')" name="stockIn" title="入库" />
        <van-tab v-if="allowedModes.includes('stockOut')" name="stockOut" title="出库" />
        <van-tab v-if="allowedModes.includes('rework')" name="rework" title="返工" />
        <van-tab v-if="allowedModes.includes('trace')" name="trace" title="追溯" />
      </van-tabs>
      <van-empty v-if="allowedModes.length === 0" description="当前账号未配置 PDA 工位" />
      <section v-else-if="mode === 'assembly'" class="form-section">
        <ProductionStation v-if="authContext && !passwordChangeVisible" :key="authContext.username" :username="authContext.username" />
      </section>
      <section v-else-if="mode === 'stockIn'" class="form-section">
        <van-dropdown-menu :overlay="false">
          <van-dropdown-item v-model="warehouseId" :options="warehouseOptions" :title="warehouseId === null ? '选择仓库' : undefined" />
          <van-dropdown-item v-model="locationId" :options="locationOptions" :title="locationId === null ? '选择库位' : undefined" />
        </van-dropdown-menu>
        <van-field ref="vehicleQrField" v-model="vehicleQrCode" label="聚合码" placeholder="扫描整车聚合码" clearable autofocus @keyup.enter="validateStockOperation('in')" />
        <van-button block type="primary" :loading="busy" @click="submitStockIn">确认入库</van-button>
      </section>
      <section v-else-if="mode === 'stockOut'" class="form-section">
        <van-tabs v-model:active="dispatchMode"><van-tab name="order" title="按订单出库" /><van-tab name="pi" title="PI 多车型整批出库" /></van-tabs>
        <PiDispatchPanel v-if="authContext && !passwordChangeVisible" v-show="dispatchMode === 'pi'" :key="authContext.username" :username="authContext.username" />
        <template v-if="dispatchMode === 'order'">
        <van-field v-model="orderKeyword" label="查找订单" placeholder="输入订单号或车型" maxlength="100" clearable :disabled="customerId === null" @keyup.enter="searchStockOutOrders">
          <template #button><van-button size="small" type="primary" :loading="orderOptionsLoading" :disabled="customerId === null" @click="searchStockOutOrders">查询</van-button></template>
        </van-field>
        <van-dropdown-menu :overlay="false">
          <van-dropdown-item v-model="customerId" :options="customerOptions" :title="customerId === null ? '选择客户' : undefined" />
          <van-dropdown-item v-model="orderId" :options="orderOptions" :title="orderId === null ? '选择订单' : undefined" :disabled="orderOptionsLoading || customerId === null" />
        </van-dropdown-menu>
        <van-cell title="待出库订单" :value="`共 ${orderTotal} 条 · 第 ${orderPage}/${orderPageCount} 页`">
          <template #label><van-button size="mini" :disabled="orderOptionsLoading || orderPage <= 1" @click="loadStockOutOrders(orderPage - 1)">上一页</van-button> <van-button size="mini" :disabled="orderOptionsLoading || orderPage >= orderPageCount" @click="loadStockOutOrders(orderPage + 1)">下一页</van-button></template>
        </van-cell>
        <van-notice-bar v-if="orderOptionsError" :text="orderOptionsError" wrapable color="#c62828" background="#fff0f0" />
        <van-cell v-else-if="!orderOptionsLoading && !orders.length && customerId !== null" title="当前客户没有匹配的待出库订单" label="可修改搜索条件后查询；只有已确认或部分出库且仍有待发数量的订单可选。" />
        <div class="stock-summary">
          <van-cell title="订单数量" :value="`${orderTotalQty} 台`" />
          <van-cell title="已出库数量" :value="`${orderShippedQty} 台`" />
          <van-cell title="本次剩余可出库" :value="`${orderRemainingQty} 台`" />
        </div>
        <van-field ref="vehicleQrField" v-model="vehicleQrCode" label="聚合码" placeholder="连续扫描整车聚合码" clearable autofocus :disabled="customerId === null || orderId === null || orderRemainingQty <= 0" @keyup.enter="scanStockOutVehicle" />
        <van-cell title="已扫描数量" :value="`${scannedVehicleQrs.length} 台`" />
        <van-tag v-for="qr in scannedVehicleQrs" :key="qr" type="success" size="large" closeable @close="scannedVehicleQrs = scannedVehicleQrs.filter(item => item !== qr)">{{ qr }}</van-tag>
        <van-button block type="primary" :loading="busy" @click="submitStockOut">确认批量出库</van-button>
        </template>
      </section>
      <section v-else-if="mode === 'rework'" class="form-section">
        <van-field ref="reworkQrField" v-model="reworkQrCode" label="聚合码" placeholder="扫描车辆聚合码" clearable autofocus @keyup.enter="focusField(reworkPartField)" />
        <van-dropdown-menu :overlay="false"><van-dropdown-item v-model="reworkPartType" :options="[{ text: '电池', value: 'BATTERY' }, { text: '电机', value: 'MOTOR' }, { text: '仪表', value: 'DISPLAY' }]" title="更换类型" /></van-dropdown-menu>
        <van-field ref="reworkPartField" v-model="reworkNewPartSn" label="新零部件" placeholder="扫描新零部件 SN" clearable @keyup.enter="focusField(reworkReasonField)" />
        <van-field ref="reworkReasonField" v-model="reworkReason" label="返工原因" placeholder="填写返工原因" type="textarea" autosize @keyup.enter="submitRework" />
        <van-button block type="primary" :loading="busy" @click="submitRework">确认换件</van-button>
      </section>
      <section v-else class="form-section">
        <van-dropdown-menu :overlay="false"><van-dropdown-item v-model="traceType" :options="[{ text: '整车聚合码', value: 'qr' },{ text: '车架 SN', value: 'frame' }, { text: '零部件 SN', value: 'part' }]" title="追溯类型" /></van-dropdown-menu>
        <van-field ref="traceField" v-model="traceKey" label="追溯编码" placeholder="扫描所选类型的编码" clearable autofocus @keyup.enter="queryTrace" />
        <van-button block type="primary" :loading="busy" @click="queryTrace">查询完整链路</van-button>
        <TraceDetails v-if="traceData" :data="traceData" @select-code="traceRelatedCode" />
      </section>
      <van-notice-bar v-if="resultMessage" :text="resultMessage" wrapable :color="resultSuccess ? '#16a36a' : '#ee0a24'" :background="resultSuccess ? '#edf9f1' : '#fff1f0'" />
    </section>
  </main>
</template>
