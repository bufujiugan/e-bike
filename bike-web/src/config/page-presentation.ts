export type Tone = 'blue' | 'green' | 'orange' | 'red' | 'purple' | 'muted'
export type Metric = { label: string; value: string | number | null; unit?: string; hint?: string; icon?: string; tone?: Tone; trend?: string; sparkline?: number[] }
export type ProcessStep = { label: string; description?: string; icon?: string; count?: number | null; percent?: number | null; state?: 'done' | 'active' | 'waiting' | 'error' }
export type ModuleData = { subtitle: string; stats: { label: string; value: string; tone: string }[]; columns: string[]; rows: string[][]; action: string; total?: number }
export type NavItem = { key: string; label: string; shortLabel: string; path?: string; icon?: string; children?: NavItem[] }
export type NavGroup = { label: string; items: NavItem[] }
export const pagePresentation: Record<string, { title: string; description: string; theme: string; motto: string }> = {
  production: { title: '生产中心', description: '四码聚合 · 作业交接 · 标签打印 · 质量隔离 · 全流程可追溯的智能制造平台', theme: 'production', motto: '数据驱动制造 · 让每一辆好车可追溯' },
  'production-tasks': { title: '生产单', description: '新建或打开生产单，在同一页完成四码绑定、标签打印和实物入库', theme: 'production', motto: '建单 → 扫码生产 → 标签打印 → 实物入库' },
  'production-skus': { title: '商品 SKU', description: '维护商品规格、标签主信息、Name 明细与商品图片', theme: 'production', motto: '规格统一 · 标签准确 · 数据可复用' },
  'production-templates': { title: '标签模板', description: '管理生产标签尺寸、标题和打印版式配置', theme: 'production', motto: '模板受控 · 打印一致 · 标识清晰' },
  'production-issues': { title: '异常隔离', description: '登记生产异常、隔离风险产品并记录解除过程', theme: 'production', motto: '异常闭环 · 风险隔离 · 质量优先' },
  'production-quarantine': { title: '旧件复检', description: '集中复检返工旧件，确认质量状态后受控放行', theme: 'production', motto: '旧件受控 · 复检留痕 · 合格复用' },
  'production-prints': { title: '打印队列', description: '查看标签打印任务、终端领取状态与补打记录', theme: 'production', motto: '队列透明 · 结果核对 · 补打可追溯' },
  'production-scan-failures': { title: '失败扫码', description: '查询扫码失败记录、失败代码与现场操作信息', theme: 'production', motto: '快速定位 · 及时纠正 · 减少重复异常' },
  'production-inventory': { title: '生产库存', description: '按商品规格、仓库与库位查看生产物料库存及隔离数量', theme: 'production', motto: '库存清晰 · 库位准确 · 隔离可见' },
  assembly: { title: '组装进度', description: '实时跟踪生产任务扫码进度与工位记录，确保生产过程透明、可视、可追溯', theme: 'assembly', motto: '精益生产 · 标准作业 · 品质可追溯' },
  vehicles: { title: '整车档案', description: '一车一码 · 全程可追溯，以聚合码为主入口，关联整车与核心部件信息', theme: 'vehicles', motto: '让每一辆车，都有可追溯的身份' },
  inventory: { title: '库存管理', description: '仓库与库位库存集中查看，物理库存、占用与隔离清晰可见', theme: 'inventory', motto: '库存透明 · 流转有序 · 高效协同' },
  'sku-inventory': { title: 'SKU 库存与锁定', description: '按具体规格核对库存，锁定后转单保留所选 SKU，连接生产与订单履约', theme: 'sku-lock', motto: '精准锁定 · 让每一份承诺可兑现' },
  'stock-in': { title: '入库记录', description: '管理整车入库业务，记录入库明细与状态，支持快速查询与数据导出', theme: 'stock-in', motto: '准确入库 · 从生产到仓储全程留痕' },
  'stock-out': { title: '出库记录', description: '管理整车出库记录与交付信息，支持快速查询、出库核对与数据导出', theme: 'stock-out', motto: '高效 · 准确 · 可追溯' },
  stock: { title: '出入库记录', description: '整车入库、出库及撤销记录，清晰掌握库存流转情况', theme: 'stock-flow', motto: '数字化流转 · 让生产更高效' },
  orders: { title: '销售订单', description: '管理客户订单，跟踪订单履约进度，从库存锁定到出库交付的全过程', theme: 'orders', motto: '连接客户 · 高效交付' },
}
export const productionFlow: ProcessStep[] = ['生产工单', '计划车辆', '作业中', '四码完成', '聚合码', '打印', '质检', '入库'].map(label => ({ label }))
export const assemblyFlow: ProcessStep[] = ['车架组装', '电机安装', '电池安装', '线束连接', '功能测试', '整车质检', '包装入库'].map(label => ({ label }))
export const traceFlow: ProcessStep[] = [
  { label: '聚合码', description: '整车唯一识别码 · 一车一码', icon: 'trace' },
  { label: '车架 SN', description: '车架身份标识 · 关联车身', icon: 'vehicles' },
  { label: '电机 SN', description: '电机身份标识 · 动力系统', icon: 'production' },
  { label: '电池 SN', description: '电池身份标识 · 能源系统', icon: 'battery' },
  { label: '仪表 SN', description: '仪表身份标识 · 控制系统', icon: 'display' },
]
export const orderFlow: ProcessStep[] = [
  { label: '销售订单', description: '创建并确认', icon: 'orders' }, { label: '库存锁定', description: '锁定可用库存', icon: 'lock' },
  { label: '生产安排', description: '生产任务协同', icon: 'production' }, { label: '质检', description: '质量检验', icon: 'roles' },
  { label: '出库', description: '出库与发运', icon: 'truck' }, { label: '交付', description: '客户签收完成', icon: 'flag' },
]
