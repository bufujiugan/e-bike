<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import axios from './api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { BarChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import * as echarts from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import PermissionGate from './components/PermissionGate.vue'
import ProductionManager from './components/ProductionManager.vue'
import { hasAnyPermission, hasPermission, hasRole, isSuperAdmin } from './permissions'

echarts.use([BarChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

type NavItem = { key: string; label: string; shortLabel: string; path?: string }

const username = ref('admin')
const password = ref('')
const loading = ref(false)
const loggedIn = ref(Boolean(localStorage.getItem('bike_access_token')))
type AuthContext = { username: string; userType: string; agentId: number | null; roles: string[]; permissions: string[]; workstations: string[]; mustChangePassword: boolean }
const authContext = ref<AuthContext | null>(null)
const isAgentAccount = computed(() => hasRole(authContext.value, 'WEB_AGENT'))
const passwordChangeVisible = ref(false)
const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const passwordChanging = ref(false)
const activeView = ref('dashboard')
const dataLoading = ref(false)
const dataError = ref('')
const importInput = ref<HTMLInputElement | null>(null)
const importTarget = ref('')
const importableModules = new Set(['parts', 'customers', 'agents', 'orders'])

type ModulePage = { subtitle: string; stats: { label: string; value: string; tone: string }[]; columns: string[]; rows: string[][]; action: string; total?: number }
const modulePageData = ref<Record<string, ModulePage>>({})

const navGroups: { label: string; items: NavItem[] }[] = [
  { label: '工作台', items: [{ key: 'dashboard', label: '运营总览', shortLabel: '总览', path: '/dashboard' }] },
  {
    label: '生产与库存',
    items: [
      { key: 'production', label: '生产中心', shortLabel: '生产', path: '/production' },
      { key: 'assembly', label: '组装进度', shortLabel: '组装', path: '/assembly' },
      { key: 'vehicles', label: '整车档案', shortLabel: '整车', path: '/vehicles' },
      { key: 'inventory', label: '库存管理', shortLabel: '库存', path: '/inventory' },
      { key: 'stock-in', label: '入库记录', shortLabel: '入库', path: '/stock-in' },
      { key: 'stock-out', label: '出库记录', shortLabel: '出库', path: '/stock-out' },
      { key: 'stock', label: '出入库记录', shortLabel: '出入', path: '/stock' },
    ],
  },
  {
    label: '业务协同',
    items: [
      { key: 'orders', label: '销售订单', shortLabel: '订单', path: '/orders' },
      { key: 'agents', label: '代理商管理', shortLabel: '代理商', path: '/master/agents' },
      { key: 'inventory-locks', label: '库存锁定', shortLabel: '锁库', path: '/inventory-locks' },
      { key: 'rework', label: '返工换件', shortLabel: '返工', path: '/rework' },
      { key: 'trace', label: '全链路追溯', shortLabel: '追溯', path: '/trace' },
    ],
  },
  {
    label: '基础资料',
    items: [
      { key: 'parts', label: '零部件管理', shortLabel: '部件', path: '/parts' },
      { key: 'customers', label: '客户管理', shortLabel: '客户', path: '/master/customers' },
      { key: 'suppliers', label: '供应商管理', shortLabel: '供应', path: '/master/suppliers' },
      { key: 'models', label: '车型管理', shortLabel: '车型', path: '/master/models' },
      { key: 'warehouses', label: '仓库与库位', shortLabel: '仓库', path: '/master/warehouses' },
      { key: 'locations', label: '库位管理', shortLabel: '库位', path: '/master/locations' },
    ],
  },
  {
    label: '系统管理',
    items: [
      { key: 'users', label: '用户管理', shortLabel: '用户', path: '/system/users' },
      { key: 'roles', label: '角色权限', shortLabel: '角色', path: '/system/roles' },
      { key: 'workstations', label: '工位配置', shortLabel: '工位', path: '/system/workstations' },
      { key: 'config', label: '系统配置', shortLabel: '配置', path: '/system/config' },
      { key: 'operation-logs', label: '操作日志', shortLabel: '日志', path: '/operation-logs' },
    ],
  },
  {
    label: '代理商工作台',
    items: [
      { key: 'agent-dashboard', label: '代理商概览', shortLabel: '概览', path: '/agent/dashboard' },
      { key: 'agent-inventory', label: '代理商库存', shortLabel: '库存', path: '/agent/inventory' },
      { key: 'agent-locks', label: '代理商锁库', shortLabel: '锁库', path: '/agent/locks' },
      { key: 'purchase-intents', label: '采购意向', shortLabel: '意向', path: '/agent/purchase-intents' },
    ],
  },
]

const navPermissionMap: Record<string, string[]> = {
  production: ['ASSEMBLY_OPERATE', 'REWORK_OPERATE', 'VEHICLE_READ'],
  dashboard: ['DASHBOARD_READ'], assembly: ['ASSEMBLY_OPERATE'], vehicles: ['VEHICLE_READ'], inventory: ['INVENTORY_READ'],
  'stock-in': ['STOCK_READ'], 'stock-out': ['STOCK_READ'], stock: ['STOCK_READ'], orders: ['ORDER_READ'],
  agents: ['MASTER_READ'], 'inventory-locks': ['AGENT_INVENTORY_READ', 'AGENT_LOCK_WRITE'], rework: ['REWORK_OPERATE'], trace: ['TRACE_READ'],
  parts: ['PART_READ'], customers: ['MASTER_READ'], suppliers: ['MASTER_READ'], models: ['MASTER_READ'], warehouses: ['MASTER_READ'], locations: ['MASTER_READ'],
  users: ['USER_READ'], roles: ['ROLE_READ'], workstations: ['WORKSTATION_READ'], config: ['SYSTEM_CONFIG_WRITE'],
  logs: ['DATA_EXPORT'], 'operation-logs': ['DATA_EXPORT'], customs: ['DATA_EXPORT'], 'agent-dashboard': ['AGENT_INVENTORY_READ'],
  'agent-inventory': ['AGENT_INVENTORY_READ'], 'agent-locks': ['AGENT_INVENTORY_READ', 'AGENT_LOCK_WRITE'], 'purchase-intents': ['AGENT_INVENTORY_READ', 'PURCHASE_INTENT_WRITE'],
}
const visibleNavGroups = computed(() => {
  const context = authContext.value
  if (!context || isSuperAdmin(context)) return navGroups
  const permissions = new Set(context.permissions)
  const isAgent = hasRole(context, 'WEB_AGENT')
  const agentOnlyViews = new Set(['agent-dashboard', 'agent-inventory', 'agent-locks', 'purchase-intents'])
  const agentExcludedViews = new Set(['inventory', 'inventory-locks'])
  return navGroups.map(group => ({
    ...group,
    items: group.items.filter(item => {
      if (isAgent && agentExcludedViews.has(item.key)) return false
      if (!isAgent && agentOnlyViews.has(item.key)) return false
      return (navPermissionMap[item.key] ?? []).some(permission => permissions.has(permission))
    }),
  })).filter(group => group.items.length > 0)
})
const userRoleLabel = computed(() => hasRole(authContext.value, 'WEB_AGENT') ? '代理商' : hasRole(authContext.value, 'WEB_OPERATOR') ? 'Web业务员' : hasRole(authContext.value, 'PDA_OPERATOR') ? 'PDA操作员' : '超级管理员')

const routeKeyMap = new Map(navGroups.flatMap((group) => group.items).map((item) => [item.path ?? `/${item.key}`, item.key]))
const initialPath = window.location.pathname
const initialKey = routeKeyMap.get(initialPath) ?? initialPath.slice(1)
if (initialKey === 'dashboard' || navGroups.some((group) => group.items.some((item) => item.key === initialKey))) activeView.value = initialKey || 'dashboard'

const activeNav = computed(() => navGroups.flatMap((group) => group.items).find((item) => item.key === activeView.value))

const activeModule = computed(() => modulePageData.value[activeView.value] ?? {
  subtitle: '基础数据与权限配置',
  stats: [],
  columns: [],
  rows: [],
  action: '新增记录',
})
const moduleSearch = ref('')
const modulePage = ref(1)
const modulePageSize = ref(20)
const moduleTotal = computed(() => modulePageData.value[activeView.value]?.total ?? 0)
const modulePageCount = computed(() => Math.max(1, Math.ceil(moduleTotal.value / modulePageSize.value)))
const filteredModuleRows = computed(() => {
  if (!activeModule.value || !moduleSearch.value.trim()) return activeModule.value?.rows ?? []
  const keyword = moduleSearch.value.trim().toLowerCase()
  return activeModule.value.rows.filter((row) => row.some((cell) => cell.toLowerCase().includes(keyword)))
})

const metrics = ref<{ label: string; value: string; unit: string; trend: string; tone: string }[]>([])
const statusRows = ref<{ label: string; count: number; color: string }[]>([])
const vehicleTotal = computed(() => statusRows.value.reduce((total, row) => total + row.count, 0).toLocaleString())
const trendRows = ref<{ date: string; assembled: number; stockIn: number; stockOut: number }[]>([])
const trendChartElement = ref<HTMLElement | null>(null)
let trendChartInstance: echarts.ECharts | null = null

function renderTrendChart() {
  if (!trendChartElement.value) return
  trendChartInstance?.dispose()
  trendChartInstance = echarts.init(trendChartElement.value)
  trendChartInstance.setOption({
    animation: false,
    grid: { left: 36, right: 12, top: 24, bottom: 28 },
    tooltip: { trigger: 'axis' },
    legend: { show: false },
    xAxis: { type: 'category', data: trendRows.value.map((row) => row.date.slice(5)), axisTick: { show: false }, axisLine: { lineStyle: { color: '#e6edf3' } }, axisLabel: { color: '#8c98a7', fontSize: 10 } },
    yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: '#eef2f6' } }, axisLabel: { color: '#8c98a7', fontSize: 10 } },
    series: [
      { name: '组装', type: 'bar', barMaxWidth: 14, data: trendRows.value.map((row) => row.assembled), itemStyle: { color: '#1677c8', borderRadius: [3, 3, 0, 0] } },
      { name: '入库', type: 'bar', barMaxWidth: 14, data: trendRows.value.map((row) => row.stockIn), itemStyle: { color: '#21af79', borderRadius: [3, 3, 0, 0] } },
      { name: '出库', type: 'bar', barMaxWidth: 14, data: trendRows.value.map((row) => row.stockOut), itemStyle: { color: '#ef8b4c', borderRadius: [3, 3, 0, 0] } },
    ],
  })
}

const quickActions = [
  { key: 'assembly', label: '开始组装', caption: '进入四码绑定工位', icon: '01' },
  { key: 'stock', label: '办理入库', caption: '扫描整车总二维码', icon: '02' },
  { key: 'inventory', label: '查看库存', caption: '按车型和库位筛选', icon: '03' },
  { key: 'trace', label: '查询追溯', caption: '输入 SN 查看档案', icon: '04' },
]

const recentOperations = ref<{ time: string; action: string; target: string; operator: string; status: string }[]>([])
const actionDialogVisible = ref(false)
const actionSubmitting = ref(false)
type ActionKind = 'part' | 'master' | 'order' | 'user' | 'role' | 'workstation' | 'config' | 'location' | 'lock' | 'rework' | 'intent'
const actionKind = ref<ActionKind>('master')
const editingId = ref<number | null>(null)
const permissionOptions = ref<Array<{ id: number; permissionCode: string; permissionName: string; permissionType: string }>>([])
const selectedPermissionIds = ref<number[]>([])
const selectedRoleIds = ref<number[]>([])
const rolesLoaded = ref(false)
const actionForm = reactive<Record<string, string | number | null>>({ code: '', name: '', contactName: '', contactPhone: '', partSn: '', partType: 'MOTOR', supplierId: null, batchNo: '', qcStatus: 'PASSED', customerId: null, agentId: null, modelId: null, warehouseId: null, orderQty: 1, plannedShipDate: '', remark: '', password: '', displayName: '', userType: 'WEB_OPERATOR', workstationId: null, roleCode: '', roleName: '', workstationCode: '', workstationName: '', workstationType: 'ASSEMBLY', configKey: '', configValue: '', lockQty: 1, expireTime: '', newPartSn: '', reason: '' })
const actionTitle = computed(() => {
  const title = ({ master: `新增${activeNav.value?.label ?? '资料'}`, part: '新增零部件', order: '新建销售订单', user: '新增用户', role: '新增角色', workstation: '新增工位', config: '更新系统配置', location: '新增库位', lock: '创建库存锁定', rework: '创建返工记录', intent: '提交采购意向' }[actionKind.value] ?? `新增${activeNav.value?.label ?? '资料'}`)
  return editingId.value === null ? title : title.replace(/^新增|^新建/, '编辑')
})
const editableMasterModules = new Set(['models', 'customers', 'suppliers', 'agents', 'warehouses'])
const canImportActiveModule = computed(() => {
  const context = authContext.value
  return Boolean(isSuperAdmin(context) && importableModules.has(activeView.value))
})
const writePermissionMap: Record<string, string> = {
  parts: 'PART_WRITE', orders: 'ORDER_WRITE',
  'inventory-locks': 'AGENT_LOCK_WRITE', 'agent-locks': 'AGENT_LOCK_WRITE',
  'purchase-intents': 'PURCHASE_INTENT_WRITE',
}
const canWriteActiveModule = computed(() => {
  const context = authContext.value
  if (!context) return false
  if (isSuperAdmin(context)) return true
  const permission = writePermissionMap[activeView.value]
  return permission !== undefined && hasPermission(context, permission)
})
const canShowPrimaryAction = computed(() => {
  if (['vehicles', 'logs', 'operation-logs', 'customs'].includes(activeView.value)) {
    return hasAnyPermission(authContext.value, ['DATA_EXPORT'])
  }
  return canWriteActiveModule.value
})
const rowActionLabel = computed(() => {
  const editable = ['users', 'roles', 'workstations', 'parts', 'orders', 'locations'].includes(activeView.value) || editableMasterModules.has(activeView.value)
  return editable && canWriteActiveModule.value ? '编辑' : '查看详情'
})

type MasterOption = { id: number; code: string; name: string; contactName?: string | null }
const masterOptions = reactive<{ customers: MasterOption[]; agents: MasterOption[]; models: MasterOption[]; suppliers: MasterOption[]; warehouses: MasterOption[] }>({ customers: [], agents: [], models: [], suppliers: [], warehouses: [] })
const roleOptions = ref<Array<{ id: number; roleCode: string; roleName: string }>>([])
const workstationOptions = ref<Array<{ id: number; workstationCode: string; workstationName: string; workstationType: string }>>([])
const detailDialogVisible = ref(false)
const detailLoading = ref(false)
const detailTitle = ref('详情')
const detailData = ref<Record<string, unknown>>({})

function authConfig() {
  return { headers: { Authorization: `Bearer ${localStorage.getItem('bike_access_token')}` } }
}

async function loadMasterOptions() {
  const types = Object.keys(masterOptions) as (keyof typeof masterOptions)[]
  const responses = await Promise.allSettled(types.map((type) => axios.get(`/api/master/${type}`, authConfig())))
  types.forEach((type, index) => {
    const response = responses[index]
    if (response.status !== 'fulfilled') return
    masterOptions[type] = (response.value.data.data ?? []).map((item: { id: number; code: string; name: string; contactName?: string | null }) => ({ id: item.id, code: item.code, name: item.name, contactName: item.contactName }))
  })
}

async function loadSystemOptions() {
  const [rolesResponse, workstationsResponse] = await Promise.all([
    axios.get('/api/roles', authConfig()),
    axios.get('/api/workstations', authConfig()),
  ])
  roleOptions.value = rolesResponse.data.data ?? []
  workstationOptions.value = workstationsResponse.data.data ?? []
}

async function loadDashboard() {
  dataLoading.value = true
  dataError.value = ''
  try {
    const [summaryResponse, trendResponse] = await Promise.all([
      axios.get('/api/dashboard/summary', authConfig()),
      axios.get('/api/dashboard/trend?days=7', authConfig()),
    ])
    const data = summaryResponse.data.data
    metrics.value = data.metrics.map((item: { label: string; value: number; unit: string; trend: string; tone: string }) => ({ ...item, value: item.value.toLocaleString() }))
    statusRows.value = data.statusRows
    recentOperations.value = data.recentOperations
    trendRows.value = trendResponse.data.data?.points ?? []
  } catch (error) {
    dataError.value = axios.isAxiosError(error) ? error.response?.data?.message ?? '运营总览数据加载失败' : '运营总览数据加载失败'
    metrics.value = []
    statusRows.value = []
    recentOperations.value = []
    trendRows.value = []
  } finally {
    dataLoading.value = false
  }
}

async function loadAuthContext() {
  const response = await axios.get('/api/auth/context', authConfig())
  authContext.value = response.data.data
}

async function loadModule(module: string) {
  if (module === 'production') return
  if (module === 'dashboard') return loadDashboard()
  dataLoading.value = true
  dataError.value = ''
  try {
    const response = await axios.get(`/api/modules/${module}`, { params: { keyword: moduleSearch.value.trim() || undefined, page: modulePage.value, pageSize: modulePageSize.value }, headers: { Authorization: `Bearer ${localStorage.getItem('bike_access_token')}` } })
    modulePageData.value[module] = response.data.data
  } catch (error) {
    dataError.value = axios.isAxiosError(error) ? error.response?.data?.message ?? '业务数据加载失败' : '业务数据加载失败'
    modulePageData.value[module] = { subtitle: '数据暂不可用', stats: [], columns: [], rows: [], action: '新增记录' }
  } finally {
    dataLoading.value = false
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
    if (!visibleNavGroups.value.flatMap(group => group.items).some(item => item.key === activeView.value)) {
      activeView.value = visibleNavGroups.value.flatMap(group => group.items)[0]?.key ?? 'dashboard'
    }
    await loadModule(activeView.value)
    ElMessage.success('登录成功')
  } catch (error) {
    const message = axios.isAxiosError(error) ? error.response?.data?.message ?? '登录失败' : '登录失败'
    ElMessage.error(message)
  } finally {
    loading.value = false
  }
}

watch([loggedIn, activeView], ([isLoggedIn]) => {
  if (isLoggedIn && !authContext.value?.mustChangePassword) void loadModule(activeView.value)
})

watch(moduleSearch, () => {
  modulePage.value = 1
  if (loggedIn.value && activeView.value !== 'dashboard') void loadModule(activeView.value)
})

watch(trendRows, () => { void nextTick(renderTrendChart) })

function changeModulePage(nextPage: number) {
  if (nextPage < 1 || nextPage > modulePageCount.value || nextPage === modulePage.value) return
  modulePage.value = nextPage
  void loadModule(activeView.value)
}

function handleAuthExpired() {
  loggedIn.value = false
  authContext.value = null
  passwordChangeVisible.value = false
}

onMounted(() => {
  window.addEventListener('bike-auth-expired', handleAuthExpired)
  window.addEventListener('popstate', handlePopState)
  if (loggedIn.value) void loadAuthContext().then(() => {
    if (authContext.value?.mustChangePassword) {
      passwordChangeVisible.value = true
      return
    }
    if (!visibleNavGroups.value.flatMap(group => group.items).some(item => item.key === activeView.value)) {
      activeView.value = visibleNavGroups.value.flatMap(group => group.items)[0]?.key ?? 'dashboard'
    }
    return loadModule(activeView.value)
  }).catch(() => logout())
})

onUnmounted(() => {
  window.removeEventListener('bike-auth-expired', handleAuthExpired)
  window.removeEventListener('popstate', handlePopState)
  trendChartInstance?.dispose()
  trendChartInstance = null
})

function routeKeyForPath(path: string) {
  const key = routeKeyMap.get(path) ?? path.replace(/^\//, '')
  return key === 'dashboard' || navGroups.some((group) => group.items.some((item) => item.key === key)) ? key : 'dashboard'
}

function syncRouteFromLocation() {
  activeView.value = routeKeyForPath(window.location.pathname)
  modulePage.value = 1
  moduleSearch.value = ''
}

function handlePopState() {
  syncRouteFromLocation()
}

function navigate(key: string) {
  activeView.value = key
  modulePage.value = 1
  moduleSearch.value = ''
  const item = navGroups.flatMap((group) => group.items).find((entry) => entry.key === key)
  const nextPath = item?.path ?? `/${key}`
  if (window.location.pathname !== nextPath) window.history.pushState(null, '', nextPath)
}

function logout() {
  localStorage.removeItem('bike_access_token')
  loggedIn.value = false
  password.value = ''
  passwordChangeVisible.value = false
}

async function submitPasswordChange() {
  if (newPassword.value.length < 8 || newPassword.value.length > 100) {
    ElMessage.error('新密码长度必须为 8-100')
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    ElMessage.error('两次输入的新密码不一致')
    return
  }
  passwordChanging.value = true
  try {
    await axios.put('/api/auth/password', { currentPassword: currentPassword.value, newPassword: newPassword.value }, authConfig())
    currentPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
    passwordChangeVisible.value = false
    if (authContext.value) authContext.value = { ...authContext.value, mustChangePassword: false }
    ElMessage.success('密码已更新')
    await loadModule(activeView.value)
  } catch (error) {
    ElMessage.error(axios.isAxiosError(error) ? error.response?.data?.message ?? '密码修改失败' : '密码修改失败')
  } finally {
    passwordChanging.value = false
  }
}

function resetActionForm() {
  editingId.value = null
  selectedPermissionIds.value = []
  selectedRoleIds.value = []
  rolesLoaded.value = false
  Object.assign(actionForm, { code: '', name: '', contactName: '', contactPhone: '', partSn: '', partType: 'MOTOR', supplierId: null, batchNo: '', qcStatus: 'PASSED', customerId: null, agentId: null, modelId: null, warehouseId: null, orderQty: 1, plannedShipDate: '', remark: '', password: '', displayName: '', userType: 'WEB_OPERATOR', workstationId: null, roleCode: '', roleName: '', workstationCode: '', workstationName: '', workstationType: 'ASSEMBLY', configKey: '', configValue: '', lockQty: 1, expireTime: '', newPartSn: '', reason: '' })
}

async function openRowAction(row: string[]) {
  const module = activeView.value
  if (!canWriteActiveModule.value) return openRowDetail(row)
  if (!['users', 'roles', 'workstations', 'parts', 'orders', 'locations'].includes(module) && !editableMasterModules.has(module)) return openRowDetail(row)
  resetActionForm()
  actionKind.value = module === 'users' ? 'user' : module === 'roles' ? 'role' : module === 'workstations' ? 'workstation' : module === 'parts' ? 'part' : 'order'
  try {
    if (module === 'parts') {
      const response = await axios.get(`/api/parts/by-sn/${encodeURIComponent(row[0])}`, authConfig())
      const part = response.data.data
      editingId.value = part.id
      Object.assign(actionForm, { partSn: part.partSn, partType: part.partType, supplierId: part.supplierId, batchNo: part.batchNo ?? '', qcStatus: part.qcStatus ?? '', remark: part.remark ?? '' })
      await loadMasterOptions()
    } else if (module === 'orders') {
      const response = await axios.get(`/api/orders/by-no/${encodeURIComponent(row[0])}`, authConfig())
      const order = response.data.data
      editingId.value = order.id
      Object.assign(actionForm, { customerId: order.customerId, agentId: order.agentId, modelId: order.modelId, orderQty: order.orderQty, plannedShipDate: order.plannedShipDate ?? '', remark: order.remark ?? '' })
      await loadMasterOptions()
    } else if (module === 'users') {
      const response = await axios.get('/api/users', { params: { keyword: row[0], page: 1, pageSize: 20 }, ...authConfig() })
      const user = (response.data.data?.records ?? []).find((item: { username: string }) => item.username === row[0])
      if (!user) throw new Error('用户记录不存在或已被更新')
      editingId.value = user.id
      Object.assign(actionForm, { code: user.username, displayName: user.displayName, userType: user.userType, agentId: user.agentId, workstationId: user.workstationId })
      await Promise.all([loadMasterOptions(), loadSystemOptions()])
      const roleResponse = await axios.get(`/api/users/${user.id}/roles`, authConfig())
      selectedRoleIds.value = roleResponse.data.data ?? []
      rolesLoaded.value = true
    } else if (module === 'roles') {
      const response = await axios.get('/api/roles', { params: { keyword: row[0] }, ...authConfig() })
      const role = (response.data.data ?? []).find((item: { roleCode: string }) => item.roleCode === row[0])
      if (!role) throw new Error('角色记录不存在或已被更新')
      editingId.value = role.id
      Object.assign(actionForm, { roleCode: role.roleCode, roleName: role.roleName })
      const [allResponse, currentResponse] = await Promise.all([
        axios.get('/api/roles/permissions', authConfig()),
        axios.get(`/api/roles/${role.id}/permissions`, authConfig()),
      ])
      permissionOptions.value = allResponse.data.data ?? []
      selectedPermissionIds.value = (currentResponse.data.data ?? []).map((item: { id: number }) => item.id)
    } else if (module === 'locations') {
      const warehouseResponse = await axios.get('/api/master/warehouses', { params: { keyword: row[1] }, ...authConfig() })
      const warehouse = (warehouseResponse.data.data ?? []).find((item: { code: string }) => item.code === row[1])
      if (!warehouse) throw new Error('所属仓库不存在或已被更新')
      const locationResponse = await axios.get(`/api/master/warehouses/${warehouse.id}/locations`, authConfig())
      const location = (locationResponse.data.data ?? []).find((item: { code: string }) => item.code === row[3])
      if (!location) throw new Error('库位不存在或已被更新')
      actionKind.value = 'location'
      editingId.value = location.id
      Object.assign(actionForm, { warehouseId: warehouse.id, code: location.code, name: location.name })
    } else if (editableMasterModules.has(module)) {
      const response = await axios.get(`/api/master/${module}`, { params: { keyword: row[0] }, ...authConfig() })
      const item = (response.data.data ?? []).find((entry: { code: string; name: string }) => entry.code === row[0] || entry.name === row[0])
      if (!item) throw new Error('基础资料不存在或已被更新')
      actionKind.value = 'master'
      editingId.value = item.id
      Object.assign(actionForm, { code: item.code, name: item.name, contactName: item.contactName ?? '', contactPhone: item.contactPhone ?? '' })
    } else {
      const response = await axios.get('/api/workstations', { params: { keyword: row[0] }, ...authConfig() })
      const workstation = (response.data.data ?? []).find((item: { workstationCode: string }) => item.workstationCode === row[0])
      if (!workstation) throw new Error('工位记录不存在或已被更新')
      editingId.value = workstation.id
      Object.assign(actionForm, { workstationCode: workstation.workstationCode, workstationName: workstation.workstationName, workstationType: workstation.workstationType })
    }
    actionDialogVisible.value = true
  } catch (error) {
    ElMessage.error(axios.isAxiosError(error) ? error.response?.data?.message ?? '编辑数据加载失败' : error instanceof Error ? error.message : '编辑数据加载失败')
  }
}

async function toggleSystemStatus(row: string[]) {
  const module = activeView.value
  if (!['users', 'roles', 'workstations', 'locations'].includes(module)) return
  try {
    let id: number | undefined
    if (module === 'users') {
      const response = await axios.get('/api/users', { params: { keyword: row[0], page: 1, pageSize: 20 }, ...authConfig() })
      id = (response.data.data?.records ?? []).find((item: { username: string }) => item.username === row[0])?.id
    } else if (module === 'roles') {
      const response = await axios.get('/api/roles', { params: { keyword: row[0] }, ...authConfig() })
      id = (response.data.data ?? []).find((item: { roleCode: string }) => item.roleCode === row[0])?.id
    } else if (module === 'workstations') {
      const response = await axios.get('/api/workstations', { params: { keyword: row[0] }, ...authConfig() })
      id = (response.data.data ?? []).find((item: { workstationCode: string }) => item.workstationCode === row[0])?.id
    } else {
      const warehouseResponse = await axios.get('/api/master/warehouses', { params: { keyword: row[1] }, ...authConfig() })
      const warehouse = (warehouseResponse.data.data ?? []).find((item: { code: string }) => item.code === row[1])
      if (warehouse) {
        const locationResponse = await axios.get(`/api/master/warehouses/${warehouse.id}/locations`, authConfig())
        id = (locationResponse.data.data ?? []).find((item: { code: string }) => item.code === row[3])?.id
      }
    }
    if (!id) throw new Error('记录不存在或已被更新')
    const endpoint = module === 'locations' ? `/api/master/locations/${id}/status` : `/api/${module === 'users' ? 'users' : module === 'roles' ? 'roles' : 'workstations'}/${id}/status`
    const statusIndex = module === 'locations' ? 4 : 3
    await axios.put(endpoint, null, { params: { enabled: row[statusIndex] !== '启用' }, ...authConfig() })
    ElMessage.success('状态已更新')
    await loadModule(activeView.value)
  } catch (error) {
    ElMessage.error(axios.isAxiosError(error) ? error.response?.data?.message ?? '状态更新失败' : error instanceof Error ? error.message : '状态更新失败')
  }
}

async function changeOrderStatus(row: string[], action: 'confirm' | 'cancel') {
  try {
    const orderResponse = await axios.get(`/api/orders/by-no/${encodeURIComponent(row[0])}`, authConfig())
    const order = orderResponse.data.data
    if (action === 'cancel') {
      const prompt = await ElMessageBox.prompt('请输入作废原因', '作废订单', { inputValue: '业务作废', inputValidator: (value: string) => value.trim() ? true : '作废原因不能为空' })
      await axios.post(`/api/orders/${order.id}/cancel`, null, { params: { reason: prompt.value }, ...authConfig() })
    } else {
      await axios.post(`/api/orders/${order.id}/confirm`, null, authConfig())
    }
    ElMessage.success(action === 'cancel' ? '订单已作废' : '订单已确认')
    await loadModule(activeView.value)
  } catch (error) {
    if (error === 'cancel' || (error && typeof error === 'object' && 'message' in error && (error as { message?: string }).message === 'cancel')) return
    ElMessage.error(axios.isAxiosError(error) ? error.response?.data?.message ?? '订单状态更新失败' : '订单状态更新失败')
  }
}

async function changeInventoryLock(row: string[], action: 'release' | 'convert') {
  try {
    const agentPath = activeView.value === 'agent-locks' ? '/api/agent/inventory-locks' : '/api/inventory-locks'
    const response = await axios.get(agentPath, { params: { page: 1, pageSize: 100 }, ...authConfig() })
    const lock = (response.data.data?.records ?? []).find((item: { lockNo: string }) => item.lockNo === row[0])
    if (!lock) throw new Error('锁定记录不存在或已被更新')
    if (action === 'release') {
      const prompt = await ElMessageBox.prompt('请输入释放原因', '释放库存锁定', { inputValue: '手工释放', inputValidator: (value: string) => value.trim() ? true : '释放原因不能为空' })
      await axios.post(`${agentPath}/${lock.id}/release`, null, { params: { reason: prompt.value }, ...authConfig() })
      ElMessage.success('库存锁定已释放')
    } else {
      const remaining = Number(lock.lockQty) - Number(lock.releasedQty) - Number(lock.convertedQty)
      const prompt = await ElMessageBox.prompt(`剩余可转订单数量：${remaining}`, '库存锁定转订单', { inputValue: String(remaining), inputValidator: (value: string) => Number.isInteger(Number(value)) && Number(value) > 0 && Number(value) <= remaining ? true : '请输入有效的转换数量' })
      await axios.post(`${agentPath}/${lock.id}/convert`, null, { params: { quantity: Number(prompt.value) }, ...authConfig() })
      ElMessage.success('库存锁定已转订单')
    }
    await loadModule(activeView.value)
  } catch (error) {
    if (error === 'cancel' || (error && typeof error === 'object' && 'message' in error && (error as { message?: string }).message === 'cancel')) return
    ElMessage.error(axios.isAxiosError(error) ? error.response?.data?.message ?? '库存锁定操作失败' : error instanceof Error ? error.message : '库存锁定操作失败')
  }
}

async function cancelStockRecord(row: string[]) {
  try {
    const type = activeView.value === 'stock-in' ? 'stock-in' : 'stock-out'
    const response = await axios.get(`/api/${type}`, { params: { page: 1, pageSize: 100 }, ...authConfig() })
    const record = (response.data.data?.records ?? []).find((item: { stockNo: string }) => item.stockNo === row[0])
    if (!record) throw new Error('出入库记录不存在或已被更新')
    const prompt = await ElMessageBox.prompt('请输入撤销原因', '撤销出入库记录', { inputValue: '业务撤销', inputValidator: (value: string) => value.trim() ? true : '撤销原因不能为空' })
    await axios.post(`/api/${type}/${record.id}/cancel`, null, { params: { reason: prompt.value }, ...authConfig() })
    ElMessage.success('记录已撤销')
    await loadModule(activeView.value)
  } catch (error) {
    if (error === 'cancel' || (error && typeof error === 'object' && 'message' in error && (error as { message?: string }).message === 'cancel')) return
    ElMessage.error(axios.isAxiosError(error) ? error.response?.data?.message ?? '撤销失败' : error instanceof Error ? error.message : '撤销失败')
  }
}

async function downloadModule(module: string) {
  try {
    const response = await axios.get(`/api/export/${module}`, { headers: { Authorization: `Bearer ${localStorage.getItem('bike_access_token')}` }, responseType: 'blob' })
    const url = URL.createObjectURL(response.data)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${module}.xlsx`
    anchor.click()
    URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch (error) {
    ElMessage.error(axios.isAxiosError(error) ? error.response?.data?.message ?? '导出失败' : '导出失败')
  }
}

function chooseImport(module: string) {
  importTarget.value = module
  if (importInput.value) importInput.value.value = ''
  importInput.value?.click()
}

async function importModule(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  const module = importTarget.value
  if (!file || !module) return
  const endpoint = module === 'parts' ? '/api/parts/import' : module === 'orders' ? '/api/orders/import' : `/api/master/${module}/import`
  const formData = new FormData()
  formData.append('file', file)
  try {
    const response = await axios.post(endpoint, formData, { headers: { ...authConfig().headers, 'Content-Type': 'multipart/form-data' } })
    const result = response.data.data
    const failed = result.failureRows ?? 0
    ElMessage[failed > 0 ? 'warning' : 'success'](`导入完成：成功 ${result.successRows} 条${failed > 0 ? `，失败 ${failed} 条` : ''}`)
    await loadModule(activeView.value)
  } catch (error) {
    ElMessage.error(axios.isAxiosError(error) ? error.response?.data?.message ?? '导入失败' : '导入失败')
  } finally {
    input.value = ''
  }
}

function openPrimaryAction() {
  const module = activeView.value
  if (module === 'vehicles' || module === 'logs' || module === 'operation-logs' || module === 'customs') return void downloadModule(module === 'operation-logs' ? 'logs' : module)
  if (module === 'inventory') return navigate('inventory-locks')
  if (module === 'assembly') return ElMessage.info('请在 PDA 组装工位执行扫码绑定')
  if (module === 'stock' || module === 'stock-in' || module === 'stock-out') return ElMessage.info('请在 PDA 工位执行扫码作业')
  if (module === 'inventory-locks' || module === 'agent-locks') actionKind.value = 'lock'
  else if (module === 'purchase-intents') actionKind.value = 'intent'
  else if (module === 'rework') actionKind.value = 'rework'
  else if (module === 'config') actionKind.value = 'config'
  else if (module === 'locations') actionKind.value = 'location'
  else if (module === 'users') actionKind.value = 'user'
  else if (module === 'roles') actionKind.value = 'role'
  else if (module === 'workstations') actionKind.value = 'workstation'
  else if (module.startsWith('agent-')) return ElMessage.info('代理商业务请使用对应专用接口')
  else actionKind.value = module === 'parts' ? 'part' : module === 'orders' ? 'order' : 'master'
  resetActionForm()
  if (actionKind.value === 'order' || actionKind.value === 'part' || actionKind.value === 'lock' || actionKind.value === 'intent' || actionKind.value === 'location') {
    void loadMasterOptions().catch(() => ElMessage.error('基础资料加载失败，请稍后重试'))
  }
  if (actionKind.value === 'user') void Promise.all([loadMasterOptions(), loadSystemOptions()]).catch(() => ElMessage.error('系统选项加载失败，请稍后重试'))
  if (actionKind.value === 'role' || actionKind.value === 'workstation') void loadSystemOptions().catch(() => ElMessage.error('系统选项加载失败，请稍后重试'))
  if (actionKind.value === 'role') void axios.get('/api/roles/permissions', authConfig()).then((response) => { permissionOptions.value = response.data.data ?? [] }).catch(() => ElMessage.error('权限列表加载失败，请稍后重试'))
  actionDialogVisible.value = true
}

async function openRowDetail(row: string[]) {
  const module = activeView.value
  detailTitle.value = `${activeNav.value?.label ?? '记录'}详情`
  detailLoading.value = true
  detailDialogVisible.value = true
  try {
    let response
    if (module === 'vehicles' && row[1]) response = await axios.get(`/api/vehicles/by-frame/${encodeURIComponent(row[1])}`, authConfig())
    else if (module === 'parts' && row[0]) response = await axios.get(`/api/parts/by-sn/${encodeURIComponent(row[0])}`, authConfig())
    else if (module === 'orders' && row[0]) response = await axios.get(`/api/orders/by-no/${encodeURIComponent(row[0])}`, authConfig())
    else if (module === 'trace' && row[1]) response = await axios.get(`/api/trace/frame/${encodeURIComponent(row[1])}`, authConfig())
    else {
      detailData.value = Object.fromEntries((activeModule.value?.columns ?? []).map((column, index) => [column, row[index] ?? '-']))
      return
    }
    detailData.value = response.data.data ?? {}
  } catch (error) {
    detailDialogVisible.value = false
    ElMessage.error(axios.isAxiosError(error) ? error.response?.data?.message ?? '详情加载失败' : '详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

async function downloadVehicleQr(print = false) {
  const vehicleId = Number(detailData.value.id)
  if (!Number.isInteger(vehicleId) || vehicleId <= 0) {
    ElMessage.error('当前详情没有可下载的整车二维码')
    return
  }
  try {
    const response = await axios.get(`/api/vehicles/${vehicleId}/qr/image`, { ...authConfig(), responseType: 'blob' })
    const url = URL.createObjectURL(response.data)
    if (print) {
      const printWindow = window.open('', '_blank', 'noopener,noreferrer')
      if (!printWindow) {
        URL.revokeObjectURL(url)
        ElMessage.error('浏览器阻止了打印窗口，请允许弹窗后重试')
        return
      }
      printWindow.document.write(`<html><head><title>整车二维码</title></head><body style="margin:0;display:grid;place-items:center;min-height:100vh"><img src="${url}" alt="整车二维码" /></body></html>`)
      printWindow.document.close()
      printWindow.addEventListener('load', () => {
        printWindow.print()
        printWindow.close()
        URL.revokeObjectURL(url)
      }, { once: true })
    } else {
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = `vehicle-${vehicleId}-qr.png`
      anchor.click()
      URL.revokeObjectURL(url)
    }
  } catch (error) {
    ElMessage.error(axios.isAxiosError(error) ? error.response?.data?.message ?? '二维码下载失败' : '二维码下载失败')
  }
}

async function regenerateVehicleQr() {
  const vehicleId = Number(detailData.value.id)
  if (!Number.isInteger(vehicleId) || vehicleId <= 0) {
    ElMessage.error('当前详情没有可更新的整车二维码')
    return
  }
  try {
    const response = await axios.post(`/api/vehicles/${vehicleId}/qr/regenerate`, null, authConfig())
    detailData.value = response.data.data ?? detailData.value
    ElMessage.success('整车二维码已重新生成')
    await loadModule('vehicles')
  } catch (error) {
    ElMessage.error(axios.isAxiosError(error) ? error.response?.data?.message ?? '二维码重新生成失败' : '二维码重新生成失败')
  }
}

async function submitPrimaryAction() {
  actionSubmitting.value = true
  try {
    let endpoint = ''
    let actionMethod: 'post' | 'put' = 'post'
    let payload: Record<string, unknown>
    if (actionKind.value === 'part') {
      endpoint = editingId.value === null ? '/api/parts' : `/api/parts/${editingId.value}`
      actionMethod = editingId.value === null ? 'post' : 'put'
      payload = editingId.value === null
        ? { partSn: actionForm.partSn, partType: actionForm.partType, supplierId: actionForm.supplierId || null, batchNo: actionForm.batchNo || null, qcStatus: actionForm.qcStatus, remark: actionForm.remark || null }
        : { supplierId: actionForm.supplierId || null, batchNo: actionForm.batchNo || null, qcStatus: actionForm.qcStatus, remark: actionForm.remark || null }
    } else if (actionKind.value === 'order') {
      endpoint = editingId.value === null ? '/api/orders' : `/api/orders/${editingId.value}`
      actionMethod = editingId.value === null ? 'post' : 'put'
      payload = { customerId: Number(actionForm.customerId), agentId: actionForm.agentId ? Number(actionForm.agentId) : null, modelId: Number(actionForm.modelId), orderQty: Number(actionForm.orderQty), plannedShipDate: actionForm.plannedShipDate || null, remark: actionForm.remark || null }
    } else if (actionKind.value === 'user') {
      endpoint = editingId.value === null ? '/api/users' : `/api/users/${editingId.value}`
      actionMethod = editingId.value === null ? 'post' : 'put'
      payload = { username: actionForm.code, password: actionForm.password, displayName: actionForm.displayName, userType: actionForm.userType, agentId: actionForm.agentId ? Number(actionForm.agentId) : null, workstationId: actionForm.workstationId ? Number(actionForm.workstationId) : null }
    } else if (actionKind.value === 'role') {
      endpoint = editingId.value === null ? '/api/roles' : `/api/roles/${editingId.value}`
      actionMethod = editingId.value === null ? 'post' : 'put'
      payload = { roleCode: actionForm.roleCode, roleName: actionForm.roleName }
    } else if (actionKind.value === 'workstation') {
      endpoint = editingId.value === null ? '/api/workstations' : `/api/workstations/${editingId.value}`
      actionMethod = editingId.value === null ? 'post' : 'put'
      payload = { workstationCode: actionForm.workstationCode, workstationName: actionForm.workstationName, workstationType: actionForm.workstationType }
    } else if (actionKind.value === 'config') {
      endpoint = `/api/system-config/${encodeURIComponent(String(actionForm.configKey))}`
      payload = { configValue: actionForm.configValue, remark: actionForm.remark || null }
    } else if (actionKind.value === 'location') {
      endpoint = editingId.value === null
        ? `/api/master/warehouses/${Number(actionForm.warehouseId)}/locations`
        : `/api/master/locations/${editingId.value}`
      actionMethod = editingId.value === null ? 'post' : 'put'
      payload = { code: actionForm.code, name: actionForm.name }
    } else if (actionKind.value === 'lock') {
      endpoint = activeView.value === 'agent-locks' ? '/api/agent/inventory-locks' : '/api/inventory-locks'
      payload = { ...(isAgentAccount.value ? {} : { agentId: Number(actionForm.agentId) }), productModelId: Number(actionForm.modelId), lockQty: Number(actionForm.lockQty), expireTime: actionForm.expireTime || null, remark: actionForm.remark || null }
    } else if (actionKind.value === 'rework') {
      endpoint = '/api/rework'
      payload = { frameSn: actionForm.code, partType: actionForm.partType, newPartSn: actionForm.newPartSn, reason: actionForm.reason }
    } else if (actionKind.value === 'intent') {
      endpoint = '/api/agent/purchase-intents'
      payload = { ...(isAgentAccount.value ? {} : { agentId: Number(actionForm.agentId) }), productModelId: Number(actionForm.modelId), intentQty: Number(actionForm.orderQty), expireTime: actionForm.expireTime || null, remark: actionForm.remark || null }
    } else if (actionKind.value === 'master') {
      endpoint = `/api/master/${activeView.value}${editingId.value === null ? '' : `/${editingId.value}`}`
      actionMethod = editingId.value === null ? 'post' : 'put'
      payload = { code: actionForm.code, name: actionForm.name, contactName: actionForm.contactName || null, contactPhone: actionForm.contactPhone || null }
    } else {
      endpoint = `/api/master/${activeView.value}`
      payload = { code: actionForm.code, name: actionForm.name, contactName: actionForm.contactName || null, contactPhone: actionForm.contactPhone || null }
    }
    const response = await axios({ method: actionMethod, url: endpoint, data: payload, ...authConfig() })
    const savedId = response.data.data?.id ?? editingId.value
    if (actionKind.value === 'user' && savedId !== null && (rolesLoaded.value || selectedRoleIds.value.length > 0)) {
      await axios.put(`/api/users/${savedId}/roles`, { roleIds: selectedRoleIds.value }, authConfig())
    }
    if (actionKind.value === 'role' && savedId !== null) {
      await axios.put(`/api/roles/${savedId}/permissions`, { permissionIds: selectedPermissionIds.value }, authConfig())
    }
    actionDialogVisible.value = false
    ElMessage.success('保存成功')
    await loadModule(activeView.value)
  } catch (error) {
    ElMessage.error(axios.isAxiosError(error) ? error.response?.data?.message ?? '保存失败' : '保存失败')
  } finally {
    actionSubmitting.value = false
  }
}
</script>

<template>
  <main v-if="!loggedIn" class="login-shell">
    <section class="login-panel">
      <p class="eyebrow">BIKE OPERATIONS</p>
      <h1>生产库存管理系统</h1>
      <p class="description">Web 管理后台</p>
      <el-form @submit.prevent="login">
        <el-form-item label="用户名">
          <el-input v-model="username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="password" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-button class="submit" type="primary" :loading="loading" native-type="submit">登录</el-button>
      </el-form>
    </section>
  </main>

  <main v-else class="admin-app">
    <header class="global-header">
      <div class="header-brand">
        <svg class="bike-logo" viewBox="0 0 80 48" aria-label="E-Bike">
          <circle cx="18" cy="34" r="11" fill="none" stroke="currentColor" stroke-width="3" />
          <circle cx="62" cy="34" r="11" fill="none" stroke="currentColor" stroke-width="3" />
          <path d="M18 34 31 15l10 19 12-19m-22 0h18m-8 0 8 19M31 15h-7m25 0h7" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round" stroke-width="3" />
        </svg>
        <div><strong>E-Bike 生产库存管理系统</strong><span>后台管理平台</span></div>
      </div>
      <nav class="header-nav" aria-label="主功能导航">
        <button v-for="item in ['高效管理', '数据驱动', '全程追溯', '连接伙伴', '助力全球出行']" :key="item" @click="navigate(item === '全程追溯' ? 'trace' : 'dashboard')"><span class="header-nav-icon">{{ item === '数据驱动' ? '▦' : item === '全程追溯' ? '◉' : item === '连接伙伴' ? '↔' : item === '助力全球出行' ? '⌁' : '▥' }}</span>{{ item }}</button>
      </nav>
      <div class="header-slogan">让每一辆 E-Bike<br /><span>高效生产 · 全球可追溯</span></div>
    </header>
    <div class="admin-shell">
      <aside class="sidebar">
      <div class="brand"><span class="brand-mark">B</span><span><strong>BIKE</strong><small>OPERATIONS</small></span></div>
      <div class="environment-badge"><span></span>开发环境 · 接口数据</div>
      <nav>
        <section v-for="group in visibleNavGroups" :key="group.label" class="nav-group">
          <p>{{ group.label }}</p>
          <button v-for="item in group.items" :key="item.key" class="nav-item" :class="{ active: activeView === item.key }" @click="navigate(item.key)"><span class="nav-icon">{{ item.shortLabel.slice(0, 1) }}</span>{{ item.label }}</button>
        </section>
      </nav>
      <div class="sidebar-footer"><div class="user-chip"><span class="avatar">A</span><span><strong>{{ username }}</strong><small>{{ userRoleLabel }}</small></span></div><button class="logout-button" @click="logout">退出登录</button></div>
      </aside>

    <section class="workspace">
      <header class="topbar"><div><p class="breadcrumb">工作台 / {{ activeNav?.label }}</p><h1>{{ activeNav?.label }}</h1></div><div class="topbar-right"><span class="last-sync">数据更新时间：刚刚</span><button class="notification" aria-label="通知">3</button><span class="top-avatar">A</span></div></header>

      <div v-if="activeView === 'dashboard'" class="dashboard-content">
        <div class="page-intro"><div><h2>今日运营概览</h2><p>生产、库存和订单状态集中查看</p><p v-if="dataLoading" class="data-hint">正在从后端加载数据...</p><p v-else-if="dataError" class="data-hint error">{{ dataError }}</p></div><span class="date-chip">{{ new Date().toLocaleDateString('zh-CN') }}</span></div>
        <section class="metric-grid"><article v-for="metric in metrics" :key="metric.label" class="metric-card" :class="metric.tone"><div class="metric-head"><span>{{ metric.label }}</span><span class="metric-dot"></span></div><div class="metric-value">{{ metric.value }}<small>{{ metric.unit }}</small></div><div class="metric-foot"><span class="trend">{{ metric.trend }}</span><span>较昨日</span></div></article></section>

        <section class="panel trend-panel"><div class="panel-title"><div><h3>近 7 日业务趋势</h3><p>组装、入库与出库数量</p></div><span class="trend-legend"><i class="assembled"></i>组装 <i class="stock-in"></i>入库 <i class="stock-out"></i>出库</span></div><div ref="trendChartElement" class="trend-chart" role="img" aria-label="近 7 日业务趋势"></div></section>

        <section class="content-grid">
          <article class="panel status-panel"><div class="panel-title"><div><h3>整车状态分布</h3><p>当前整车生命周期状态</p></div><button class="text-button" @click="navigate('vehicles')">查看档案</button></div><div class="status-visual"><div class="donut"><strong>{{ vehicleTotal }}</strong><span>整车总量</span></div><div class="status-list"><div v-for="row in statusRows" :key="row.label" class="status-row"><span class="status-name"><i :style="{ background: row.color }"></i>{{ row.label }}</span><strong>{{ row.count.toLocaleString() }}</strong><div class="status-bar"><span :style="{ width: `${vehicleTotal === '0' ? 0 : Math.min(100, row.count / Math.max(1, Number(vehicleTotal.replace(',', '')) / 100))}%`, background: row.color }"></span></div></div></div></div></article>
          <article class="panel quick-panel"><div class="panel-title"><div><h3>快捷操作</h3><p>常用业务入口</p></div></div><div class="quick-grid"><button v-for="action in quickActions" :key="action.key" class="quick-action" @click="navigate(action.key)"><span class="quick-icon">{{ action.icon }}</span><span><strong>{{ action.label }}</strong><small>{{ action.caption }}</small></span><b>›</b></button></div></article>
        </section>

        <section class="panel operations-panel"><div class="panel-title"><div><h3>最近操作</h3><p>关键业务操作实时记录</p></div><button class="text-button" @click="navigate('trace')">查看全部</button></div><div class="table-wrap"><table><thead><tr><th>时间</th><th>操作</th><th>业务对象</th><th>操作人</th><th>状态</th></tr></thead><tbody><tr v-for="operation in recentOperations" :key="operation.time"><td class="muted">{{ operation.time }}</td><td><strong>{{ operation.action }}</strong></td><td>{{ operation.target }}</td><td>{{ operation.operator }}</td><td><span class="status-pill" :class="{ processing: operation.status === '进行中' }">{{ operation.status }}</span></td></tr></tbody></table></div></section>
      </div>

      <ProductionManager v-else-if="activeView === 'production' && !passwordChangeVisible" :can-manage="isSuperAdmin(authContext)" :can-operate="hasPermission(authContext, 'ASSEMBLY_OPERATE')" :can-report-issue="hasAnyPermission(authContext, ['ASSEMBLY_OPERATE','REWORK_OPERATE'])" />
      <div v-else class="module-content">
        <div class="module-heading"><div><p class="eyebrow">{{ activeNav?.shortLabel.toUpperCase() }} MODULE</p><h2>{{ activeNav?.label }}</h2><p>{{ activeModule?.subtitle }}</p><p v-if="dataLoading" class="data-hint">正在从后端加载数据...</p><p v-else-if="dataError" class="data-hint error">{{ dataError }}</p></div><PermissionGate :allowed="canShowPrimaryAction"><div class="module-actions"><button v-if="canImportActiveModule" class="secondary-action" @click="chooseImport(activeView)">导入 Excel</button><button class="primary-action" @click="openPrimaryAction">＋ {{ activeModule?.action }}</button><input ref="importInput" class="hidden-file-input" type="file" accept=".xlsx,.xls" @change="importModule" /></div></PermissionGate></div>
        <section class="module-stats"><article v-for="stat in activeModule?.stats" :key="stat.label" class="module-stat" :class="stat.tone"><span>{{ stat.label }}</span><strong>{{ stat.value }}</strong></article></section>
        <section class="panel data-panel"><div class="filter-row"><div class="search-box"><span>⌕</span><input v-model="moduleSearch" :placeholder="`搜索${activeNav?.label}...`" /><button v-if="moduleSearch" aria-label="清空搜索" @click="moduleSearch = ''">×</button></div><button class="filter-button" @click="loadModule(activeView)">筛选</button><button class="reset-button" @click="moduleSearch = ''">重置</button></div><div class="table-wrap"><table><thead><tr><th v-for="column in activeModule?.columns" :key="column">{{ column }}</th><th>操作</th></tr></thead><tbody><tr v-for="(row, index) in filteredModuleRows" :key="`${row[0]}-${index}`"><td v-for="cell in row" :key="cell">{{ cell }}</td><td><button class="row-action" @click="openRowAction(row)">{{ rowActionLabel }}</button><button v-if="canWriteActiveModule && ['users', 'roles', 'workstations', 'locations'].includes(activeView)" class="row-action" @click="toggleSystemStatus(row)">{{ row[activeView === 'locations' ? 4 : 3] === '启用' ? '停用' : '启用' }}</button><button v-if="canWriteActiveModule && activeView === 'orders' && ['DRAFT', 'PENDING', '草稿', '待确认'].includes(row[6])" class="row-action" @click="changeOrderStatus(row, 'confirm')">确认</button><button v-if="canWriteActiveModule && activeView === 'orders' && !['SHIPPED', 'PARTIAL_SHIPPED', 'CANCELLED', '已完成', '部分出库', '已作废'].includes(row[6])" class="row-action danger-action" @click="changeOrderStatus(row, 'cancel')">作废</button><button v-if="canWriteActiveModule && ['inventory-locks', 'agent-locks'].includes(activeView) && ['锁定中', '部分转订单', 'LOCKED', 'PARTIAL_CONVERTED'].includes(row[7])" class="row-action" @click="changeInventoryLock(row, 'release')">释放</button><button v-if="canWriteActiveModule && ['inventory-locks', 'agent-locks'].includes(activeView) && ['锁定中', '部分转订单', 'LOCKED', 'PARTIAL_CONVERTED'].includes(row[7]) && Number(row[3]) - Number(row[4]) - Number(row[5]) > 0" class="row-action" @click="changeInventoryLock(row, 'convert')">转订单</button><button v-if="canWriteActiveModule && ['stock-in', 'stock-out'].includes(activeView) && ['已完成', 'COMPLETED'].includes(row[6])" class="row-action danger-action" @click="cancelStockRecord(row)">撤销</button></td></tr><tr v-if="filteredModuleRows.length === 0"><td :colspan="(activeModule?.columns.length ?? 0) + 1" class="empty-cell">没有匹配的数据</td></tr></tbody></table></div><div class="table-footer"><span>共 {{ activeModule?.total ?? filteredModuleRows.length }} 条数据</span><span class="pagination"><label class="page-size-label">每页 <select v-model.number="modulePageSize" @change="modulePage = 1; loadModule(activeView)"><option :value="10">10</option><option :value="20">20</option><option :value="50">50</option><option :value="100">100</option></select> 条</label><button class="page-button" :disabled="modulePage <= 1" @click="changeModulePage(modulePage - 1)">上一页</button><span>第 {{ modulePage }} / {{ modulePageCount }} 页</span><button class="page-button" :disabled="modulePage >= modulePageCount" @click="changeModulePage(modulePage + 1)">下一页</button></span><span class="preview-note">数据来源：后端接口</span></div></section>
      </div>
      </section>
    </div>
    <el-dialog v-model="actionDialogVisible" :title="actionTitle" width="520px">
      <el-form v-if="actionKind === 'part'" label-width="100px">
        <el-form-item label="零部件 SN"><el-input v-model="actionForm.partSn" :disabled="editingId !== null" /></el-form-item>
        <el-form-item label="零部件类型"><el-select v-model="actionForm.partType" :disabled="editingId !== null"><el-option label="电机" value="MOTOR" /><el-option label="电池" value="BATTERY" /><el-option label="仪表" value="DISPLAY" /></el-select></el-form-item>
        <el-form-item label="供应商"><el-select v-model="actionForm.supplierId" clearable filterable placeholder="请选择供应商"><el-option v-for="item in masterOptions.suppliers" :key="item.id" :label="`${item.name}（${item.code}）`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="批次号"><el-input v-model="actionForm.batchNo" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="actionForm.remark" type="textarea" /></el-form-item>
      </el-form>
      <el-form v-else-if="actionKind === 'order'" label-width="100px">
        <el-form-item label="客户"><el-select v-model="actionForm.customerId" filterable placeholder="请选择客户"><el-option v-for="item in masterOptions.customers" :key="item.id" :label="`${item.name}（${item.code}）`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="代理商"><el-select v-model="actionForm.agentId" clearable filterable placeholder="可选代理商"><el-option v-for="item in masterOptions.agents" :key="item.id" :label="`${item.name}（${item.code}）`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="车型"><el-select v-model="actionForm.modelId" filterable placeholder="请选择车型"><el-option v-for="item in masterOptions.models" :key="item.id" :label="`${item.name}（${item.code}）`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="订单数量"><el-input-number v-model="actionForm.orderQty" :min="1" /></el-form-item>
        <el-form-item label="计划发货"><el-date-picker v-model="actionForm.plannedShipDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="actionForm.remark" type="textarea" /></el-form-item>
      </el-form>
      <el-form v-else-if="actionKind === 'user'" label-width="100px">
        <el-form-item label="用户名"><el-input v-model="actionForm.code" autocomplete="username" :disabled="editingId !== null" /></el-form-item>
        <el-form-item v-if="editingId === null" label="初始密码"><el-input v-model="actionForm.password" type="password" show-password autocomplete="new-password" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="actionForm.displayName" /></el-form-item>
        <el-form-item label="账号类型"><el-select v-model="actionForm.userType"><el-option label="Web业务员" value="WEB_OPERATOR" /><el-option label="PDA操作员" value="PDA_OPERATOR" /><el-option label="代理商" value="AGENT" /></el-select></el-form-item>
        <el-form-item label="代理商"><el-select v-model="actionForm.agentId" clearable filterable><el-option v-for="item in masterOptions.agents" :key="item.id" :label="`${item.name}（${item.code}）`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="工位"><el-select v-model="actionForm.workstationId" clearable filterable><el-option v-for="item in workstationOptions" :key="item.id" :label="`${item.workstationName}（${item.workstationCode}）`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="角色"><el-select v-model="selectedRoleIds" multiple clearable filterable><el-option v-for="item in roleOptions" :key="item.id" :label="`${item.roleName}（${item.roleCode}）`" :value="item.id" /></el-select></el-form-item>
      </el-form>
      <el-form v-else-if="actionKind === 'role'" label-width="100px">
        <el-form-item label="角色编码"><el-input v-model="actionForm.roleCode" /></el-form-item>
        <el-form-item label="角色名称"><el-input v-model="actionForm.roleName" /></el-form-item>
        <el-form-item label="功能权限"><el-checkbox-group v-model="selectedPermissionIds"><el-checkbox v-for="item in permissionOptions" :key="item.id" :value="item.id">{{ item.permissionName }}（{{ item.permissionCode }}）</el-checkbox></el-checkbox-group></el-form-item>
      </el-form>
      <el-form v-else-if="actionKind === 'workstation'" label-width="100px">
        <el-form-item label="工位编码"><el-input v-model="actionForm.workstationCode" /></el-form-item>
        <el-form-item label="工位名称"><el-input v-model="actionForm.workstationName" /></el-form-item>
        <el-form-item label="工位类型"><el-select v-model="actionForm.workstationType"><el-option label="车架工位" value="FRAME" /><el-option label="电机工位" value="MOTOR" /><el-option label="电池工位" value="BATTERY" /><el-option label="仪表工位" value="DISPLAY" /><el-option label="车灯工位" value="LIGHT" /><el-option label="总装工位" value="ASSEMBLY" /><el-option label="入库" value="STOCK_IN" /><el-option label="出库" value="STOCK_OUT" /><el-option label="返工" value="REWORK" /></el-select></el-form-item>
      </el-form>
      <el-form v-else-if="actionKind === 'config'" label-width="100px">
        <el-form-item label="配置键"><el-input v-model="actionForm.configKey" /></el-form-item>
        <el-form-item label="配置值"><el-input v-model="actionForm.configValue" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="actionForm.remark" type="textarea" /></el-form-item>
      </el-form>
      <el-form v-else-if="actionKind === 'location'" label-width="100px">
        <el-form-item label="仓库"><el-select v-model="actionForm.warehouseId" filterable :disabled="editingId !== null"><el-option v-for="item in masterOptions.warehouses" :key="item.id" :label="`${item.name}（${item.code}）`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="库位编码"><el-input v-model="actionForm.code" /></el-form-item>
        <el-form-item label="库位名称"><el-input v-model="actionForm.name" /></el-form-item>
      </el-form>
      <el-form v-else-if="actionKind === 'lock'" label-width="100px">
        <el-form-item v-if="!isAgentAccount" label="代理商"><el-select v-model="actionForm.agentId" filterable><el-option v-for="item in masterOptions.agents" :key="item.id" :label="`${item.name}（${item.code}）`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="车型"><el-select v-model="actionForm.modelId" filterable><el-option v-for="item in masterOptions.models" :key="item.id" :label="`${item.name}（${item.code}）`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="锁定数量"><el-input-number v-model="actionForm.lockQty" :min="1" /></el-form-item>
        <el-form-item label="有效期"><el-date-picker v-model="actionForm.expireTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="actionForm.remark" type="textarea" /></el-form-item>
      </el-form>
      <el-form v-else-if="actionKind === 'rework'" label-width="100px">
        <el-form-item label="车架 SN"><el-input v-model="actionForm.code" /></el-form-item>
        <el-form-item label="更换类型"><el-select v-model="actionForm.partType"><el-option label="电机" value="MOTOR" /><el-option label="电池" value="BATTERY" /><el-option label="仪表" value="DISPLAY" /></el-select></el-form-item>
        <el-form-item label="新零部件 SN"><el-input v-model="actionForm.newPartSn" /></el-form-item>
        <el-form-item label="返工原因"><el-input v-model="actionForm.reason" type="textarea" /></el-form-item>
      </el-form>
      <el-form v-else-if="actionKind === 'intent'" label-width="100px">
        <el-form-item v-if="!isAgentAccount" label="代理商"><el-select v-model="actionForm.agentId" filterable><el-option v-for="item in masterOptions.agents" :key="item.id" :label="`${item.name}（${item.code}）`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="车型"><el-select v-model="actionForm.modelId" filterable><el-option v-for="item in masterOptions.models" :key="item.id" :label="`${item.name}（${item.code}）`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="意向数量"><el-input-number v-model="actionForm.orderQty" :min="1" /></el-form-item>
        <el-form-item label="有效期"><el-date-picker v-model="actionForm.expireTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="actionForm.remark" type="textarea" /></el-form-item>
      </el-form>
      <el-form v-else label-width="100px">
        <el-form-item label="编码"><el-input v-model="actionForm.code" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="actionForm.name" /></el-form-item>
        <el-form-item label="联系人"><el-input v-model="actionForm.contactName" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="actionForm.contactPhone" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="actionDialogVisible = false">取消</el-button><el-button type="primary" :loading="actionSubmitting" @click="submitPrimaryAction">保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="detailDialogVisible" :title="detailTitle" width="680px">
      <div v-loading="detailLoading" class="detail-content">
        <el-descriptions v-if="Object.keys(detailData).length" :column="2" border>
          <el-descriptions-item v-for="(value, key) in detailData" :key="key" :label="String(key)">{{ typeof value === 'object' ? JSON.stringify(value) : String(value ?? '-') }}</el-descriptions-item>
        </el-descriptions>
        <el-empty v-else description="暂无详情数据" />
      </div>
      <template #footer>
        <el-button v-if="activeView === 'vehicles' && detailData.id" @click="downloadVehicleQr(false)">下载二维码</el-button>
        <el-button v-if="activeView === 'vehicles' && detailData.id" type="primary" @click="downloadVehicleQr(true)">打印二维码</el-button>
        <el-button v-if="activeView === 'vehicles' && detailData.id && canWriteActiveModule" type="warning" @click="regenerateVehicleQr">重新生成二维码</el-button>
        <el-button @click="detailDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="passwordChangeVisible" title="首次登录请修改密码" width="460px" :close-on-click-modal="false" :show-close="false">
      <el-form label-width="110px">
        <el-form-item label="当前密码"><el-input v-model="currentPassword" type="password" show-password autocomplete="current-password" /></el-form-item>
        <el-form-item label="新密码"><el-input v-model="newPassword" type="password" show-password autocomplete="new-password" /></el-form-item>
        <el-form-item label="确认新密码"><el-input v-model="confirmPassword" type="password" show-password autocomplete="new-password" /></el-form-item>
      </el-form>
      <template #footer><el-button type="primary" :loading="passwordChanging" @click="submitPasswordChange">更新密码</el-button></template>
    </el-dialog>
  </main>
</template>
