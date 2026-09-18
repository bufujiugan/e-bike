<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import axios from './api'
import { showFailToast, showSuccessToast } from 'vant'
import { hasPermission, isSuperAdmin } from './permissions'
import ProductionStation from './components/ProductionStation.vue'

const username = ref('admin')
const password = ref('')
const loading = ref(false)
const loggedIn = ref(Boolean(localStorage.getItem('bike_access_token')))
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
const reworkFrameField = ref<ScanFieldRef | null>(null)
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
const orders = ref<Array<{ id: number; orderNo: string; customerId: number; modelName: string; orderQty: number; shippedQty: number; status: string }>>([])
const warehouses = ref<Array<{ id: number; name: string; code: string; status: string }>>([])
const locations = ref<Array<{ id: number; name: string; code: string; status: string }>>([])
const traceKey = ref('')
const traceType = ref<'frame' | 'part' | 'qr'>('frame')
const reworkFrameSn = ref('')
const reworkPartType = ref('BATTERY')
const reworkNewPartSn = ref('')
const reworkReason = ref('')
const resultMessage = ref('')
const resultSuccess = ref(false)
const busy = ref(false)
const tokenHeaders = computed(() => ({ Authorization: `Bearer ${localStorage.getItem('bike_access_token')}` }))
const customerOptions = computed(() => customers.value.map(customer => ({ text: `${customer.name} (${customer.code})`, value: customer.id })))
const orderOptions = computed(() => orders.value
  .filter(order => customerId.value === null || order.customerId === customerId.value)
  .filter(order => order.orderQty - order.shippedQty > 0)
  .map(order => ({
    text: `${order.orderNo} · ${order.modelName} · 剩余 ${Math.max(0, order.orderQty - order.shippedQty - (order.id === orderId.value ? scannedVehicleQrs.value.length : 0))} 台`,
    value: order.id,
  })))
const selectedOrder = computed(() => orders.value.find(order => order.id === orderId.value) ?? null)
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
  try {
    const [customerResponse, orderResponse] = await Promise.all([
      axios.get('/api/master/customers', { headers: tokenHeaders.value }),
      axios.get('/api/orders?page=1&pageSize=100', { headers: tokenHeaders.value })
    ])
    customers.value = customerResponse.data.data.filter((customer: { status: string }) => customer.status === 'ENABLED')
    orders.value = orderResponse.data.data.records.filter((order: { status: string; orderQty: number; shippedQty: number }) =>
      ['CONFIRMED', 'PARTIAL_SHIPPED'].includes(order.status) && order.orderQty > order.shippedQty)
    if (customerId.value === null && customers.value.length > 0) customerId.value = customers.value[0].id
  } catch (error) {
    showFailToast(axios.isAxiosError(error) ? error.response?.data?.message ?? '客户和订单加载失败' : '客户和订单加载失败')
  }
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
    if (authContext.value) authContext.value = { ...authContext.value, mustChangePassword: false }
    showSuccessToast('密码已更新')
    await loadOptionsForMode(mode.value)
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
    resultMessage.value = `入库完成：${response.data.data.frameSn}`
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
  if (!reworkFrameSn.value.trim() || !reworkNewPartSn.value.trim() || !reworkReason.value.trim()) {
    resultMessage.value = '请填写车架、新零部件和返工原因'
    showFailToast(resultMessage.value)
    return
  }
  busy.value = true
  try {
    const response = await axios.post('/api/rework', { frameSn: reworkFrameSn.value, partType: reworkPartType.value, newPartSn: reworkNewPartSn.value, reason: reworkReason.value }, { headers: tokenHeaders.value })
    resultMessage.value = `返工完成：${response.data.data.reworkNo}，新部件 ${response.data.data.newPartSn}`
    resultSuccess.value = true
    reworkFrameSn.value = ''
    reworkNewPartSn.value = ''
    reworkReason.value = ''
    focusField(reworkFrameField)
    showSuccessToast('返工完成')
  } catch (error) {
    resultMessage.value = axios.isAxiosError(error) ? error.response?.data?.message ?? '返工失败' : '返工失败'
    resultSuccess.value = false
    showFailToast(resultMessage.value)
  } finally { busy.value = false }
}

async function queryTrace() {
  busy.value = true
  try {
    if (!traceKey.value.trim()) throw new Error('请输入追溯标识')
    const response = await axios.get(`/api/trace/${traceType.value}/${encodeURIComponent(traceKey.value.trim())}`, { headers: tokenHeaders.value })
    const vehicle = response.data.data.vehicle
    resultMessage.value = `${vehicle.frameSn} · ${vehicle.statusName} · ${vehicle.modelName ?? '-'} · 绑定历史 ${response.data.data.bindingHistory.length} 条`
    resultSuccess.value = true
    showSuccessToast('追溯查询完成')
  } catch (error) { resultMessage.value = axios.isAxiosError(error) ? error.response?.data?.message ?? '追溯失败' : error instanceof Error ? error.message : '追溯失败'; resultSuccess.value = false; showFailToast(resultMessage.value) } finally { busy.value = false; focusField(traceField) }
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

function logout() { localStorage.removeItem('bike_access_token'); loggedIn.value = false; passwordChangeVisible.value = false }

function handleAuthExpired() {
  loggedIn.value = false
  authContext.value = null
  passwordChangeVisible.value = false
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
    if (nextMode === 'rework') focusField(reworkFrameField)
    if (nextMode === 'trace') focusField(traceField)
  }
})

watch(customerId, () => {
  if (orderId.value !== null && !orderOptions.value.some(order => order.value === orderId.value)) orderId.value = null
  scannedVehicleQrs.value = []
  vehicleQrCode.value = ''
})

watch(orderId, (nextOrderId, previousOrderId) => {
  if (nextOrderId !== previousOrderId) {
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
          <van-dropdown-item v-model="warehouseId" :options="warehouseOptions" title="选择仓库" />
          <van-dropdown-item v-model="locationId" :options="locationOptions" title="选择库位" />
        </van-dropdown-menu>
        <van-field ref="vehicleQrField" v-model="vehicleQrCode" label="总二维码" placeholder="扫描整车总二维码" clearable autofocus @keyup.enter="validateStockOperation('in')" />
        <van-button block type="primary" :loading="busy" @click="submitStockIn">确认入库</van-button>
      </section>
      <section v-else-if="mode === 'stockOut'" class="form-section">
        <van-dropdown-menu :overlay="false">
          <van-dropdown-item v-model="customerId" :options="customerOptions" title="选择客户" />
          <van-dropdown-item v-model="orderId" :options="orderOptions" title="选择订单" />
        </van-dropdown-menu>
        <div class="stock-summary">
          <van-cell title="订单数量" :value="`${orderTotalQty} 台`" />
          <van-cell title="已出库数量" :value="`${orderShippedQty} 台`" />
          <van-cell title="本次剩余可出库" :value="`${orderRemainingQty} 台`" />
        </div>
        <van-field ref="vehicleQrField" v-model="vehicleQrCode" label="总二维码" placeholder="连续扫描整车总二维码" clearable autofocus :disabled="customerId === null || orderId === null || orderRemainingQty <= 0" @keyup.enter="scanStockOutVehicle" />
        <van-cell title="已扫描数量" :value="`${scannedVehicleQrs.length} 台`" />
        <van-tag v-for="qr in scannedVehicleQrs" :key="qr" type="success" size="large" closeable @close="scannedVehicleQrs = scannedVehicleQrs.filter(item => item !== qr)">{{ qr }}</van-tag>
        <van-button block type="primary" :loading="busy" @click="submitStockOut">确认批量出库</van-button>
      </section>
      <section v-else-if="mode === 'rework'" class="form-section">
        <van-field ref="reworkFrameField" v-model="reworkFrameSn" label="车架 SN" placeholder="扫描车架 SN" clearable autofocus @keyup.enter="focusField(reworkPartField)" />
        <van-dropdown-menu :overlay="false"><van-dropdown-item v-model="reworkPartType" :options="[{ text: '电池', value: 'BATTERY' }, { text: '电机', value: 'MOTOR' }, { text: '仪表', value: 'DISPLAY' }]" title="更换类型" /></van-dropdown-menu>
        <van-field ref="reworkPartField" v-model="reworkNewPartSn" label="新零部件" placeholder="扫描新零部件 SN" clearable @keyup.enter="focusField(reworkReasonField)" />
        <van-field ref="reworkReasonField" v-model="reworkReason" label="返工原因" placeholder="填写返工原因" type="textarea" autosize @keyup.enter="submitRework" />
        <van-button block type="primary" :loading="busy" @click="submitRework">确认换件</van-button>
      </section>
      <section v-else class="form-section">
        <van-dropdown-menu :overlay="false"><van-dropdown-item v-model="traceType" :options="[{ text: '整车聚合码', value: 'qr' },{ text: '车架 SN', value: 'frame' }, { text: '零部件 SN', value: 'part' }]" title="追溯类型" /></van-dropdown-menu>
        <van-field ref="traceField" v-model="traceKey" label="追溯编码" placeholder="扫描所选类型的编码" clearable autofocus @keyup.enter="queryTrace" />
        <van-button block type="primary" :loading="busy" @click="queryTrace">查询完整链路</van-button>
      </section>
      <van-notice-bar v-if="resultMessage" :text="resultMessage" wrapable :color="resultSuccess ? '#16a36a' : '#ee0a24'" :background="resultSuccess ? '#edf9f1' : '#fff1f0'" />
    </section>
  </main>
</template>
