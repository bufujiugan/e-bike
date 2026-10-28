<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { hasAnyPermission, hasPermission, hasRole, type PermissionContext } from '../permissions'
import AppIcon from './AppIcon.vue'
import ProductionFulfillmentDiagrams from './ProductionFulfillmentDiagrams.vue'

const props = defineProps<{ modelValue: boolean; context: PermissionContext | null; initialView?: 'flow' | 'sequence' | 'steps' }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; stage: [value: string]; navigate: [value: string] }>()
const activeView = ref<'flow' | 'sequence' | 'steps'>(props.initialView ?? 'flow')
const selectedStep = ref<number | null>(null)
const guideBody = ref<HTMLElement>()
async function showStep(index: number) {
  selectedStep.value = index
  activeView.value = 'steps'
  await nextTick()
  const element = guideBody.value?.querySelector<HTMLElement>(`[data-guide-step="${index}"]`)
  element?.scrollIntoView({ block: 'start', behavior: 'auto' })
  element?.focus({ preventScroll: true })
}
watch(activeView, () => {
  if (guideBody.value) guideBody.value.scrollTop = 0
})
watch(() => props.modelValue, async (visible) => {
  if (!visible) return
  activeView.value = props.initialView ?? 'flow'
  selectedStep.value = null
  await nextTick()
  if (guideBody.value) guideBody.value.scrollTop = 0
})

type GuideDestination =
  | { label: string; kind: 'stage'; key: 'skus' | 'tasks' | 'prints' | 'inventory' | 'locks' | 'orders' }
  | { label: string; kind: 'module'; key: 'models' | 'parts' | 'production-templates' }

type GuideStep = {
  title: string
  owner: string
  menu: string
  actions: string[]
  result: string
  next: string
  destination?: GuideDestination
  pdaLink?: boolean
}

const steps: GuideStep[] = [
  {
    title: '确认车型', owner: '生产计划员', menu: 'Web 后台 → 资料与设置 → 车型管理',
    actions: ['搜索并确认计划生产的车型已建立且处于启用状态。'],
    result: 'SKU 可选择对应车型。', next: '车型确认后，检查要使用的标签模板。',
    destination: { label: '打开车型管理', kind: 'module', key: 'models' },
  },
  {
    title: '确认标签模板', owner: '生产计划员', menu: 'Web 后台 → 生产作业 → 生产总览与辅助工具 → 标签模板',
    actions: ['选择已配置好的标签尺寸、标题和打印版式；新模板先保存并启用。'],
    result: '创建 SKU 时可以关联该模板。', next: '模板准备好后，创建具体商品 SKU。',
    destination: { label: '打开标签模板', kind: 'module', key: 'production-templates' },
  },
  {
    title: '创建 SKU', owner: '生产计划员', menu: 'Web 后台 → 生产作业 → 商品与标签资料；或我的工作台 → 商品准备',
    actions: ['点击“新增 SKU”。', '选择车型，填写 SKU 编码、商品名称、规格等资料。', '填写 EAN/UPC 等标签信息并选择标签模板；如有多组标签 Name 明细，也在本 SKU 中逐条维护，然后保存。'],
    result: '得到后续工单、库存和订单所使用的商品规格。一个 SKU 可以对应多台车。', next: 'SKU 建好后登记每台车要绑定的三个部件 SN。',
    destination: { label: '打开商品准备', kind: 'stage', key: 'skus' },
  },
  {
    title: '登记三个部件', owner: '仓管/来料登记人员', menu: 'Web 后台 → 资料与设置 → 零部件管理',
    actions: ['按实物登记电机、电池、仪表 SN。', '分别选择正确的部件类型，填写供应商、批次和质检状态。', '扫码前确认 SN 未重复占用且质检状态符合要求。'],
    result: '三个部件编码可在生产绑定时识别。车架 SN 来自实物，当前后台没有单独维护车架号的页面；新车架在 PDA 选择“车架”类型后扫描或手动录入实际号码，不能随意编写。', next: '部件准备好后，按 SKU 和批次下达一张批量生产工单。',
    destination: { label: '打开零部件管理', kind: 'module', key: 'parts' },
  },
  {
    title: '创建生产单', owner: '生产计划员', menu: 'Web 后台 → 生产作业 → 生产单；或我的工作台 → 生产办理',
    actions: ['点击“新建生产单”。', '选择商品 SKU，填写本批次编号和计划数量，例如 100 台；没有所需商品时可在建单窗口内新增。', '确认后点击“创建并开始生产”，直接进入本单办理页。'],
    result: '创建一张关联 SKU、批次和计划数量的工单；不需要按每台车分别建单。计划生产 100 台不代表已有 100 台库存。', next: '把工单号或 SKU/批次告知 PDA 操作人员。',
    destination: { label: '打开生产单', kind: 'stage', key: 'tasks' },
  },
  {
    title: '扫描绑定四码', owner: '组装工位操作员', menu: 'Web 后台 → 生产作业 → 生产单 → 扫码生产；或 PDA/H5 → 组装',
    actions: ['Web 可打开生产单后直接开始新车；PDA 可输入工单号、SKU 或批次搜索并选择生产单。', '依次扫描车架、电机、电池、仪表，顺序不限。新车架无法自动识别时，明确选择“车架”类型。', '同一台车完成前不要混入下一台的部件；扫码结果不确定时先刷新本单车辆核对，禁止直接重复首码。'],
    result: '首码自动建立单车记录；第四码完成后系统绑定四码、生成总码并创建首次打印任务。Web 保留当前车辆继续办理，PDA 进入下一台扫码准备。', next: '四码完成后核对打印任务和实物标签。',
    destination: { label: '打开生产单', kind: 'stage', key: 'tasks' },
    pdaLink: true,
  },
  {
    title: '打印并核对标签', owner: '打印工位操作员', menu: 'Web 后台 → 生产单 → 标签打印；或 PDA/H5 → 组装 → 上一台已完成',
    actions: ['在当前生产单选择已完成四码的车辆，进入“标签打印”预览并提交打印；也可由 PDA 或自动打印终端领取。', '核对实物标签上的总码和条码。提交到打印队列不代表标签已经实际出纸，必须以实物和打印回报为准。'],
    result: '车辆标签可用于后续仓储扫码。', next: '打印核对后，扫描整车总码办理入库。',
    destination: { label: '打开打印队列', kind: 'stage', key: 'prints' },
    pdaLink: true,
  },
  {
    title: '办理入库', owner: '入库仓管员', menu: 'Web 后台 → 生产单 → 办理入库；或 PDA/H5 → 入库',
    actions: ['选择实际收货的仓库和库位。', '再次扫描实物标签上的整车总码，与当前车辆核对一致后点击“确认本车入库”。'],
    result: '入库成功后车辆进入可用库存；Web 可在“仓储管理 → 库存管理 → 入库记录”查询。', next: '按要承诺给客户的 SKU 数量创建库存锁定。',
    destination: { label: '打开生产单', kind: 'stage', key: 'tasks' },
    pdaLink: true,
  },
  {
    title: '锁定 SKU 库存', owner: '销售/库存管理员', menu: 'Web 后台 → 我的工作台 → 库存与锁定',
    actions: ['按 SKU 核对“可新增锁定”数量，将目标 SKU 加入锁定，在“待锁定商品”中填写数量。', '点击“选择客户并锁定”或“锁定所选 SKU”，选择客户、可选代理商和有效期，再点击“确认锁定”。'],
    result: '只占用指定数量，不锁整个 SKU，也不指定车架 SN。若可用 100 台且无其他限制，A 锁 30 台后，B 仍可锁 30 台，余量为 40 台；同时须满足 SKU 和车型剩余库存上限。', next: '在锁定转订单环节将锁定数量转成销售订单。',
    destination: { label: '打开库存与锁定', kind: 'stage', key: 'inventory' },
  },
  {
    title: '锁定转销售订单', owner: '销售/订单管理员', menu: 'Web 后台 → 我的工作台 → 锁定转订单',
    actions: ['找到锁定记录，点击“转订单”。', '核对固定 SKU、客户、转换数量和计划出库日，点击“创建订单”。'],
    result: '生成销售订单并保留对应库存占用；页面会直接打开新订单办理。', next: '进入订单办理确认销售订单；如有关联 PI，先完成 PI 放行。',
    destination: { label: '打开锁定转订单', kind: 'stage', key: 'locks' },
  },
  {
    title: '确认订单与 PI 放行', owner: '销售/订单管理员', menu: 'Web 后台 → 我的工作台 → 订单与出库',
    actions: ['打开目标订单，核对客户、数量、库存占用和计划出库日。', '点击“确认订单，继续出库”。', '若订单关联 PI，在订单办理中打开“本单 PI 与发货放行”，完成 PI 确认、到账登记和发货放行。'],
    result: '订单进入待出库状态；PI 订单还需满足发货放行条件。', next: 'PDA 按客户和订单扫描实际要发出的整车总码。',
    destination: { label: '打开订单与出库', kind: 'stage', key: 'orders' },
  },
  {
    title: 'PDA 批量出库', owner: '出库仓管员', menu: 'PDA/H5 → 出库 → 按订单出库',
    actions: ['先选择客户，再搜索并选择“待出库”或“部分出库且有待发数量”的订单。', '连续扫描每台整车的总码，核对订单数量和本次扫描数量。', '点击“确认批量出库”。'],
    result: '系统校验客户、订单余量、车辆库存和总码；成功后更新订单为部分出库或已完成。', next: '回 Web 订单办理核对已出库车辆和出库单。',
    pdaLink: true,
  },
]

const pdaUrl = computed(() => {
  const url = new URL(window.location.href)
  if (!['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)) return ''
  url.port = '5174'
  url.pathname = '/'
  url.search = ''
  url.hash = ''
  return url.toString()
})
function canOpen(step: GuideStep) {
  const destination = step.destination
  if (!destination) return false
  if (destination.kind === 'module') {
    if (destination.key === 'models') return hasPermission(props.context, 'MASTER_READ')
    if (destination.key === 'parts') return hasPermission(props.context, 'PART_READ')
    return !hasRole(props.context, 'WEB_AGENT') && hasAnyPermission(props.context, ['ASSEMBLY_OPERATE','REWORK_OPERATE','VEHICLE_READ','PRODUCTION_MANAGE'])
  }
  if (['skus', 'tasks', 'prints'].includes(destination.key)) {
    return !hasRole(props.context, 'WEB_AGENT') && hasAnyPermission(props.context, ['ASSEMBLY_OPERATE','REWORK_OPERATE','VEHICLE_READ','PRODUCTION_MANAGE','STOCK_IN_OPERATE'])
  }
  if (destination.key === 'inventory') {
    return !hasRole(props.context, 'PDA_OPERATOR') && hasAnyPermission(props.context, ['INVENTORY_READ','AGENT_INVENTORY_READ'])
  }
  if (destination.key === 'locks') {
    return !hasRole(props.context, 'WEB_AGENT') && !hasRole(props.context, 'PDA_OPERATOR') && hasAnyPermission(props.context, ['AGENT_INVENTORY_READ','AGENT_LOCK_WRITE'])
  }
  return hasPermission(props.context, 'ORDER_READ')
}
function open(step: GuideStep) {
  const destination = step.destination
  if (!destination || !canOpen(step)) return
  emit('update:modelValue', false)
  if (destination.kind === 'stage') emit('stage', destination.key)
  else emit('navigate', destination.key)
}
function close() { emit('update:modelValue', false) }
</script>

<template>
  <el-dialog :model-value="modelValue" title="生产到出库操作说明" width="min(1240px, 96vw)" top="4vh" :append-to-body="true" body-class="production-fulfillment-guide-body" @close="close">
    <div class="guide-view-switch" role="group" aria-label="操作说明视图">
      <el-button :type="activeView === 'flow' ? 'primary' : 'default'" :aria-pressed="activeView === 'flow'" @click="activeView = 'flow'">业务流程图</el-button>
      <el-button :type="activeView === 'sequence' ? 'primary' : 'default'" :aria-pressed="activeView === 'sequence'" @click="activeView = 'sequence'">角色时序图</el-button>
      <el-button :type="activeView === 'steps' ? 'primary' : 'default'" :aria-pressed="activeView === 'steps'" @click="selectedStep = null; activeView = 'steps'">逐步操作</el-button>
    </div>
    <div ref="guideBody" class="guide-body">
      <div v-if="activeView !== 'sequence'" class="guide-intro"><strong>从车型准备到订单出库，按顺序逐步办理</strong><span>Web 后台负责资料、生产单、扫码打印、库存和订单；PDA/H5 提供面向现场的组装、入库和出库操作。</span></div>
      <div v-if="activeView !== 'sequence'" class="guide-entry"><span>当前 Web：工作台 → 我的工作台；页面右上角可随时重新打开本说明。正式环境请使用已配置的 PDA/H5 地址；本机联调地址：<a v-if="pdaUrl" :href="pdaUrl" target="_blank" rel="noopener noreferrer">{{ pdaUrl }}</a><span v-else>请联系管理员获取</span></span></div>
      <ProductionFulfillmentDiagrams v-if="activeView !== 'steps'" :mode="activeView" @select="showStep" />
      <ol v-else class="guide-steps">
        <li v-for="(step, index) in steps" :key="step.title" class="guide-step" :class="{ 'is-selected': selectedStep === index }" :data-guide-step="index" tabindex="-1">
          <div class="step-index" :class="`tone-${index % 4}`">{{ String(index + 1).padStart(2, '0') }}</div>
          <div class="step-content">
            <div class="step-heading"><div><h3>{{ step.title }}</h3><span class="step-owner">{{ step.owner }}</span></div><div class="step-links"><el-button v-if="canOpen(step)" link type="primary" @click="open(step)">{{ step.destination?.label }}</el-button><a v-if="step.pdaLink && pdaUrl" :href="pdaUrl" target="_blank" rel="noopener noreferrer">打开本机 PDA/H5 <AppIcon name="arrow" /></a></div></div>
            <p class="step-menu"><strong>操作位置</strong> {{ step.menu }}</p>
            <ol class="step-actions"><li v-for="action in step.actions" :key="action">{{ action }}</li></ol>
            <div class="step-outcomes"><p><strong>完成后：</strong>{{ step.result }}</p><p><strong>下一步：</strong>{{ step.next }}</p></div>
          </div>
        </li>
      </ol>
      <el-alert title="边界提醒" type="warning" :closable="false" show-icon>
        建生产单不会逐车建单；Web 或 PDA 的首码会自动建立单车记录。第四码自动生成总码和打印任务，但打印核对、入库、锁定转订单、订单确认和 PDA 出库都必须由对应岗位实际操作。
      </el-alert>
    </div>
    <template #footer><el-button @click="close">关闭说明</el-button></template>
  </el-dialog>
</template>

<style scoped>
.guide-view-switch{display:flex;flex-shrink:0;gap:8px;padding:0 0 14px}.guide-view-switch .el-button{margin:0}.guide-step.is-selected{background:#f0f6ff;border-radius:9px;outline:2px solid #a8cdf9;outline-offset:-2px}.guide-step{scroll-margin-top:8px}.guide-step:focus-visible{outline:2px solid #1677ff;outline-offset:-2px}@media(max-width:600px){.guide-view-switch{gap:6px}.guide-view-switch .el-button{flex:1;padding:8px 6px;font-size:12px}}
:global(.el-dialog > .el-dialog__body.production-fulfillment-guide-body){display:flex;flex-direction:column;min-height:0;overflow:hidden}.guide-body{flex:1 1 auto;min-height:0;max-height:none;overflow-y:auto;overflow-x:hidden;overscroll-behavior:contain;padding:0 6px 8px 0}.guide-intro{display:grid;gap:7px;padding:16px 18px;background:#f1f7fc;border-left:4px solid #1767ad;border-radius:5px}.guide-intro strong{font-size:16px;color:#173a5b}.guide-intro span,.guide-entry{font-size:13px;color:#60758a;line-height:1.7}.guide-entry{padding:12px 18px;border-bottom:1px solid #e4ebf1}.guide-entry a{color:#1767ad;overflow-wrap:anywhere}.guide-steps{list-style:none;margin:0;padding:4px 0 8px}.guide-step{display:grid;grid-template-columns:42px minmax(0,1fr);gap:14px;padding:17px 4px;border-bottom:1px solid #e9eef3}.step-index{width:34px;height:34px;display:grid;place-items:center;border-radius:50%;font-size:12px;font-weight:700;background:#e8f1fb;color:#1767ad}.tone-1{background:#eaf5ed;color:#35814c}.tone-2{background:#fff2e4;color:#b66821}.tone-3{background:#e8f3f2;color:#247c75}.step-content{min-width:0}.step-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.step-heading>div{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.step-heading h3{margin:0;font-size:15px;color:#243d54}.step-owner{font-size:12px;color:#6b7d8e;background:#f2f5f8;padding:4px 8px;border-radius:4px}.step-menu{margin:9px 0;font-size:13px;color:#1767ad;overflow-wrap:anywhere}.step-menu strong{color:#425a70}.step-actions{margin:8px 0;padding-left:20px;color:#394d60;font-size:13px;line-height:1.8}.step-outcomes{display:grid;gap:4px;padding:9px 12px;background:#f8fafc;border-radius:5px}.step-outcomes p{margin:0;font-size:12px;line-height:1.7;color:#53677a}.step-outcomes strong{color:#244b68}.step-links{display:flex!important;align-items:center;gap:12px;flex-wrap:wrap}.step-heading a{display:inline-flex;align-items:center;gap:5px;font-size:13px;color:#1767ad;text-decoration:none;white-space:nowrap}.step-heading a:hover{text-decoration:underline}.step-heading :deep(.app-icon){width:15px;height:15px}.guide-body :deep(.el-alert){margin-top:10px}@media(max-width:600px){.guide-step{grid-template-columns:30px minmax(0,1fr);gap:9px;padding:14px 0}.step-index{width:28px;height:28px;font-size:11px}.step-heading{align-items:flex-start}.step-menu,.step-actions{font-size:12px}.guide-intro{padding:13px}.guide-entry{padding:10px 12px}}
</style>
