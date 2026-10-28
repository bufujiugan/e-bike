<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { hasAnyPermission, hasPermission, hasRole, type PermissionContext } from '../permissions'
import ProductionManager from './ProductionManager.vue'
import SkuInventoryPanel from './SkuInventoryPanel.vue'
import FulfillmentQueue from './FulfillmentQueue.vue'
import PiWorkspace from './PiWorkspace.vue'
import AppIcon from './AppIcon.vue'

const props = defineProps<{ context: PermissionContext | null; username: string; refreshKey: number }>()
const emit = defineEmits<{ navigate: [key: string]; guide: []; convert: [lockNo: string]; order: [orderNo: string] }>()
const stepIcons: Record<string, string> = { tasks: 'tasks', skus: 'inventory', prints: 'printer', inventory: 'network', orders: 'orders', locks: 'tasks', pis: 'info' }
const areaWatermarks: Record<string, string> = { production: 'chart', sales: 'truck', warehouse: 'home' }
type SkuContext = { id:number; skuCode:string; modelId:number }
function readSkuContext(): SkuContext | undefined {
  const query = new URLSearchParams(location.search)
  const id = Number(query.get('skuId')), modelId = Number(query.get('modelId')), skuCode = query.get('skuCode')
  return Number.isSafeInteger(id) && id > 0 && Number.isSafeInteger(modelId) && modelId > 0 && skuCode ? { id, modelId, skuCode } : undefined
}
const selectedSku = ref<SkuContext | undefined>(readSkuContext())
const internal = computed(() => !hasRole(props.context, 'WEB_AGENT') && !hasRole(props.context, 'PDA_OPERATOR'))
const productionRead = computed(() => !hasRole(props.context, 'WEB_AGENT') && hasAnyPermission(props.context,['ASSEMBLY_OPERATE','REWORK_OPERATE','VEHICLE_READ','PRODUCTION_MANAGE','STOCK_IN_OPERATE','STOCK_OUT_OPERATE']))
const steps = computed(() => [
  { key:'skus', title:'商品准备', detail:'规格与标签', allowed:productionRead.value },
  { key:'tasks', title:'生产单', detail:'建单 · 扫码 · 打印 · 入库', allowed:productionRead.value },
  { key:'prints', title:'打印队列', detail:'标签与补打', allowed:productionRead.value },
  { key:'inventory', title:'库存与锁定', detail:'核对库存 · 为客户锁定', allowed:!hasRole(props.context,'PDA_OPERATOR') && hasAnyPermission(props.context,['INVENTORY_READ','AGENT_INVENTORY_READ']) },
  { key:'locks', title:'锁定转订单', detail:'原地转单 · 保留占用', allowed:internal.value && hasAnyPermission(props.context,['AGENT_INVENTORY_READ','AGENT_LOCK_WRITE']) },
  { key:'orders', title:'订单与出库', detail:'确认 · 发货 · 出库明细', allowed:hasPermission(props.context,'ORDER_READ') },
  { key:'pis', title:'PI 与履约', detail:'收款 · 放行 · 交付', allowed:hasPermission(props.context,'PI_READ') && !hasRole(props.context,'PDA_OPERATOR') },
].filter(step => step.allowed))
const areas = computed(() => [
  { key: 'production', title: '生产作业', icon: 'production', description: '新建或打开一张生产单，在同一页完成扫码、打印和入库。', hint: '建单 → 扫码生产 → 标签打印 → 实物入库', keys: ['tasks', 'skus', 'prints'] },
  { key: 'sales', title: '销售与交付', icon: 'orders', description: '围绕一张订单，完成从锁定到交付的办理。', hint: '锁定转单 → 订单确认 → PI 与交付', keys: ['orders', 'locks', 'pis'] },
  { key: 'warehouse', title: '库存与锁定', icon: 'inventory', description: '按商品核对可用库存，为客户锁定所需数量。', hint: '核对规格 → 查看库存 → 客户锁定', keys: ['inventory'] },
].map(area => ({ ...area, steps: area.keys.flatMap(key => steps.value.filter(step => step.key === key)) })).filter(area => area.steps.length))
const shortcuts = computed(() => [
  { key: 'dashboard', title: '运营概览', detail: '查看生产与运营数据', icon: 'dashboard', allowed: hasPermission(props.context, 'DASHBOARD_READ') },
  { key: 'trace', title: '全链路追溯', detail: '按整车码追溯生产记录', icon: 'trace', allowed: hasPermission(props.context, 'TRACE_READ') },
  { key: 'after-sales', title: '售后案例', detail: '跟进客户反馈与处理', icon: 'service', allowed: hasPermission(props.context, 'AFTER_SALES_READ') },
].filter(shortcut => shortcut.allowed))
const stage = ref(new URLSearchParams(location.search).get('stage') || 'home')
const currentStep = computed(() => steps.value.find(step => step.key === stage.value))
const currentArea = computed(() => areas.value.find(area => area.steps.some(step => step.key === stage.value)))
const recentStage = ref('')
const recentStep = computed(() => steps.value.find(step => step.key === recentStage.value))
const recentStorageKey = computed(() => `bike_workbench_recent:${props.username}`)
function readRecentStage() {
  try { recentStage.value = sessionStorage.getItem(recentStorageKey.value) || '' }
  catch { console.warn('当前浏览器无法读取最近办理入口，本次仅在页面内保留。') }
}
function syncStage() {
  const requested = new URLSearchParams(location.search).get('stage') || 'home'
  stage.value = requested === 'home' || steps.value.some(step => step.key === requested) ? requested : 'home'
  selectedSku.value = readSkuContext()
}
function updateLocation(push: boolean) {
  const url = new URL(location.href)
  if (stage.value === 'home') url.searchParams.delete('stage')
  else url.searchParams.set('stage', stage.value)
  for (const key of ['skuId', 'modelId', 'skuCode']) url.searchParams.delete(key)
  if (selectedSku.value) {
    url.searchParams.set('skuId', String(selectedSku.value.id))
    url.searchParams.set('modelId', String(selectedSku.value.modelId))
    url.searchParams.set('skuCode', selectedSku.value.skuCode)
  }
  if (url.href !== location.href) {
    if (push) history.pushState(null, '', url)
    else history.replaceState(null, '', url)
  }
}
function choose(key: string) {
  if (key !== 'home' && !steps.value.some(step => step.key === key)) return
  stage.value = key
  updateLocation(true)
}
function nextStage(key: string, sku?: SkuContext) {
  if (!steps.value.some(step => step.key === key)) return
  if (sku) selectedSku.value = sku
  choose(key)
}
function clearSku() { selectedSku.value = undefined; updateLocation(false) }
function navigate(key: string) {
  const targets: Record<string,string> = { 'production-tasks':'tasks','production-skus':'skus','production-prints':'prints','production-inventory':'inventory','sku-inventory':'inventory','inventory-locks':'locks', orders:'orders',pis:'pis' }
  if (targets[key] && steps.value.some(step => step.key === targets[key])) choose(targets[key]); else emit('navigate',key)
}
watch(steps, () => {
  if (props.context && stage.value !== 'home' && !currentStep.value) { stage.value = 'home'; updateLocation(false) }
}, { immediate: true })
watch(recentStorageKey, readRecentStage, { immediate: true })
watch(currentStep, step => {
  if (!step) return
  recentStage.value = step.key
  try { sessionStorage.setItem(recentStorageKey.value, step.key) }
  catch { console.warn('当前浏览器无法保存最近办理入口，本次仅在页面内保留。') }
}, { immediate: true })
onMounted(() => window.addEventListener('popstate',syncStage))
onUnmounted(() => window.removeEventListener('popstate',syncStage))
</script>
<template>
  <div class="business-workspace" :class="{ 'workbench-home': stage === 'home' }">
    <template v-if="stage === 'home'">
      <section class="workbench-welcome">
        <div class="welcome-copy"><span class="workflow-eyebrow">BIKE / 工作空间</span><h1>把手上的事，接着办好。</h1><p>{{ username }}，欢迎回来。从当前业务出发，找到下一步。</p><button class="welcome-guide" @click="emit('guide')"><AppIcon name="layers" />查看生产到交付操作指南<AppIcon name="arrow" /></button></div>
        <div class="resume-card"><span class="resume-label"><AppIcon name="clock" />{{ recentStep ? '最近办理 · 当前会话' : '开始今天的工作' }}</span><strong>{{ recentStep?.title || '选择一个业务开始' }}</strong><p>{{ recentStep?.detail || '下方按业务整理了常用入口，办理后可从这里快速返回。' }}</p><button v-if="recentStep" class="resume-button" @click="choose(recentStep.key)">继续办理<AppIcon name="arrow" /></button><span v-else class="resume-empty">你的最近办理入口将保留在这里</span><span v-if="!recentStep" class="resume-decoration" aria-hidden="true"><AppIcon name="arrow" /></span></div>
      </section>
      <div v-if="selectedSku" class="workflow-context"><span><AppIcon name="inventory" />当前商品 <strong>{{ selectedSku.skuCode }}</strong><small>进入生产或库存时继续使用</small></span><el-button link @click="clearSku">清除商品范围</el-button></div>
      <section class="workbench-business" aria-label="业务办理入口">
        <div class="workbench-section-title"><div><h2>今天要办什么？</h2><p>选择业务，再围绕具体记录继续办理。</p></div><span>按当前账号权限展示</span></div>
        <div class="workbench-areas">
          <article v-for="area in areas" :key="area.key" class="workbench-area" :class="`area-${area.key}`">
            <div class="area-heading">
              <span class="area-icon"><AppIcon :name="area.icon" /></span>
              <div class="area-heading-copy"><h3>{{ area.title }}</h3><p class="area-description">{{ area.description }}</p></div>
              <AppIcon class="area-watermark" :name="areaWatermarks[area.key]" />
            </div>
            <div class="area-steps">
              <button v-for="step in area.steps" :key="step.key" @click="choose(step.key)">
                <span class="step-icon"><AppIcon :name="stepIcons[step.key]" /></span>
                <span class="step-copy"><strong>{{ step.title }}</strong><small>{{ step.detail }}</small></span>
                <AppIcon class="step-chevron" name="chevron" />
              </button>
            </div>
            <p class="area-hint"><span>{{ area.hint }}</span><AppIcon name="arrow" /></p>
          </article>
        </div>
        <el-empty v-if="!areas.length" description="当前账号暂无可办理业务，请从左侧进入已授权页面。" />
      </section>
      <section v-if="shortcuts.length" class="workbench-shortcuts" aria-label="查询与跟进"><div class="workbench-section-title"><div><h2>查询与跟进</h2><p>需要了解进度或处理问题时，从这里进入。</p></div></div><div class="shortcut-grid"><button v-for="shortcut in shortcuts" :key="shortcut.key" @click="emit('navigate', shortcut.key)"><span class="shortcut-icon"><AppIcon :name="shortcut.icon" /></span><span class="shortcut-copy"><strong>{{ shortcut.title }}</strong><small>{{ shortcut.detail }}</small></span><AppIcon name="arrow" /></button></div></section>
    </template>
    <section v-else class="workflow-header">
      <div class="workflow-heading"><div class="workflow-location"><button class="workflow-back" @click="choose('home')"><AppIcon name="home" />工作台首页</button><span>/</span><h2>{{ currentArea?.title }}</h2></div><el-button text @click="emit('guide')">操作指南</el-button></div>
      <nav class="workflow-areas" aria-label="业务分区"><button v-for="area in areas" :key="area.key" :class="{ active: currentArea?.key === area.key }" :aria-pressed="currentArea?.key === area.key" @click="choose(area.steps[0]!.key)"><AppIcon :name="area.icon" />{{ area.title }}</button></nav>
      <nav class="workflow-stages" aria-label="业务办理环节"><button v-for="step in currentArea?.steps" :key="step.key" :class="{ active: stage === step.key }" :aria-current="stage === step.key ? 'page' : undefined" @click="choose(step.key)">{{ step.title }}</button><span class="workflow-stage-hint">{{ currentStep?.detail }}</span></nav>
      <div v-if="selectedSku" class="workflow-context"><span><AppIcon name="inventory" />当前商品 <strong>{{ selectedSku.skuCode }}</strong><small>用于商品、生产和库存查询；订单与 PI 仍展示全部记录</small></span><el-button link @click="clearSku">清除商品范围</el-button></div>
    </section>
    <template v-if="currentStep">
    <ProductionManager v-if="['skus','tasks','prints'].includes(stage) && productionRead" :key="stage + ':' + (selectedSku?.id ?? '')" :view="stage" :username="username" :context="context" :initial-sku-id="selectedSku?.id" :initial-keyword="stage === 'skus' ? selectedSku?.skuCode : undefined" :can-manage="internal && hasPermission(context,'PRODUCTION_MANAGE')" :can-operate="hasPermission(context,'ASSEMBLY_OPERATE')" :can-report-issue="hasAnyPermission(context,['ASSEMBLY_OPERATE','REWORK_OPERATE'])" @navigate="navigate" @order="emit('order',$event)" @continue="nextStage" />
    <SkuInventoryPanel v-else-if="stage === 'inventory'" :key="selectedSku?.id ?? 'all'" :context="context" :initial-sku-id="selectedSku?.id" :initial-model-id="selectedSku?.modelId" @navigate="navigate" @convert="emit('convert',$event)" />
    <FulfillmentQueue v-else-if="stage === 'locks' || stage === 'orders'" :key="stage" :kind="stage" :context="context" :refresh-key="refreshKey" @convert="emit('convert',$event)" @order="emit('order',$event)" />
    <PiWorkspace v-else-if="stage === 'pis'" :context="context" @navigate="navigate" />
    </template>
  </div>
</template>
<style scoped>
.business-workspace { min-width: 0; display: grid; grid-template-columns: minmax(0, 1fr); gap: 24px; margin: 26px; color: #203b56; }
.business-workspace:has(.production-order-workbench) > .workflow-header { display: none; }
.business-workspace > :deep(.pi-workspace) { width: 100%; max-width: 100%; box-sizing: border-box; margin: 0; background: white; padding: 20px; }
.business-workspace > :deep(.production-panel) { min-width: 0; }
.business-workspace :deep(.pi-heading) { margin: 0 0 18px; padding: 12px 0; }
.business-workspace :deep(.pi-filters > *) { max-width: 100%; }
.business-workspace button { font: inherit; cursor: pointer; }
.business-workspace button:focus-visible { outline: 3px solid #85b9ff; outline-offset: 3px; }
.app-icon { width: 19px; height: 19px; flex-shrink: 0; }
.workbench-home { gap: 20px; margin: 18px 26px 14px 18px; color: #09214b; font-family: 'Microsoft YaHei', 'PingFang SC', sans-serif; background: radial-gradient(ellipse at 50% 45%, #e4f3ff80, transparent 75%); }
.workbench-welcome { position: relative; isolation: isolate; overflow: hidden; display: grid; grid-template-columns: minmax(0, 1fr) 338px; gap: 28px; align-items: center; min-height: 240px; box-sizing: border-box; padding: 22px 18px 20px 30px; border: 1px solid #e1effb; border-radius: 14px; background: linear-gradient(110deg, #eaf5ff, #e7f3ff 60%, #f1f8ff); }
.workbench-welcome::before { content: ''; position: absolute; z-index: -1; inset: 0 340px 0 auto; width: 640px; background: url('/images/workbench-factory-reference.png') center / cover no-repeat; mask-image: linear-gradient(90deg, transparent, #000 23%, #000 93%, transparent); }
.welcome-copy { min-width: 0; }
.workflow-eyebrow { color: #44658e; font-size: 14px; font-weight: 500; letter-spacing: .3px; }
.welcome-copy h1 { margin: 15px 0 10px; color: #061b40; font-size: clamp(26px, 2.1vw, 34px); font-weight: 700; line-height: 1.4; letter-spacing: .4px; }
.welcome-copy > p { margin: 0 0 15px; font-size: 15px; color: #102b54; line-height: 1.8; }
.business-workspace .welcome-guide { display: inline-flex; align-items: center; justify-content: center; gap: 12px; min-height: 46px; max-width: 100%; box-sizing: border-box; padding: 12px 28px; margin: 0; border: 0; border-radius: 9px; background: #0066ff; color: #fff; font-size: 14px; font-weight: 600; letter-spacing: .4px; box-shadow: 0 4px 12px #0066ff18; }
.welcome-guide:hover { background: #0057db; }
.welcome-guide .app-icon { width: 21px; height: 21px; color: #fff; }
.welcome-guide .app-icon:last-child { width: 18px; margin-left: 2px; }
.resume-card { position: relative; box-sizing: border-box; min-height: 196px; border: 1px solid #ffffffd9; background: #ffffffeb; padding: 20px 24px 16px; border-radius: 13px; box-shadow: 0 5px 15px #416aa005; }
.resume-label { display: flex; align-items: center; gap: 10px; font-size: 13px; color: #53709a; }
.resume-label .app-icon { width: 17px; height: 17px; }
.resume-card > strong { display: block; margin-top: 16px; font-size: 20px; font-weight: 700; }
.resume-card p { max-width: 220px; min-height: 42px; font-size: 14px; color: #526e98; line-height: 1.6; margin: 8px 38px 14px 0; }
.resume-decoration { position: absolute; top: 73px; right: 24px; width: 48px; height: 48px; display: grid; place-items: center; color: #0066ff; background: #f0f7ff; border-radius: 50%; }
.resume-decoration .app-icon { width: 25px; height: 25px; }
.resume-button { width: 100%; display: flex; align-items: center; justify-content: space-between; padding: 10px 12px; color: #fff; background: #0066ff; border: 0; border-radius: 6px; font-size: 13px !important; }
.resume-button:hover { background: #0057db; }
.resume-empty { display: block; padding-top: 11px; border-top: 1px solid #e8eef5; color: #6b82a3; font-size: 12px; }
.workbench-section-title { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; margin-bottom: 10px; }
.workbench-section-title h2 { position: relative; margin: 0; padding-left: 18px; color: #09214b; font-size: 22px; font-weight: 700; line-height: 1.35; }
.workbench-section-title h2::before { content: ''; position: absolute; left: 0; top: 4px; width: 4px; height: 23px; border-radius: 1px; background: #0066ff; }
.workbench-section-title p { margin: 3px 0 0 16px; color: #5c769d; font-size: 13px; line-height: 1.6; }
.workbench-section-title > span { padding-top: 7px; font-size: 13px; color: #526f96; }
.workbench-areas { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px; }
.workbench-area { --area-color: #0066ff; --area-bg: #e5efff; position: relative; display: flex; flex-direction: column; min-width: 0; min-height: 366px; box-sizing: border-box; padding: 21px 20px 15px; border: 1px solid #f4f9ff; border-radius: 12px; background: #ffffffed; box-shadow: 0 4px 18px #436f9b03; }
.area-production { --area-color: #00a394; --area-bg: #e0f8f3; }
.area-warehouse { --area-color: #ff8900; --area-bg: #fff2e6; }
.area-heading { position: relative; display: flex; align-items: center; gap: 16px; min-height: 64px; margin-bottom: 14px; }
.area-icon { display: grid; place-items: center; flex: 0 0 64px; height: 64px; border-radius: 13px; color: var(--area-color); background: radial-gradient(ellipse at 70% 75%, var(--area-bg), #ffffff66), var(--area-bg); }
.area-icon .app-icon { width: 34px; height: 34px; stroke-width: 1.8; filter: drop-shadow(0 3px 4px #428cbb14); }
.area-heading-copy { position: relative; z-index: 1; flex: 1; min-width: 0; }
.area-heading h3 { margin: 0; color: #09214b; font-size: 20px; line-height: 1.4; font-weight: 700; }
.area-description { color: #637c9f; font-size: 12px; line-height: 1.65; margin: 6px 0 0; }
.area-heading > .area-watermark { position: absolute; top: -4px; right: 0; width: 48px; height: 48px; color: #c9e0ff; opacity: .6; stroke-width: 2.5; }
.area-steps { display: grid; gap: 9px; margin-bottom: 12px; padding-top: 11px; border-top: 3px solid #d0d4da; }
.area-steps button { display: flex; align-items: center; gap: 14px; min-width: 0; min-height: 56px; box-sizing: border-box; text-align: left; padding: 6px 13px; border: 1px solid transparent; border-radius: 9px; background: linear-gradient(110deg, #f6faff, #f3f7fb); color: #09214b; transition: background .15s, border-color .15s; }
.area-steps button:hover { border-color: #bbd9ff; background: #edf5ff; }
.step-icon { display: grid; place-items: center; flex: 0 0 35px; height: 35px; border-radius: 8px; background: #ffffffa6; color: #0066ff; }
.area-warehouse .step-icon { color: var(--area-color); }
.step-icon .app-icon { width: 25px; height: 25px; stroke-width: 1.8; }
.step-copy { flex: 1; min-width: 0; }
.area-steps strong { display: block; font-size: 14px; line-height: 1.5; font-weight: 700; }
.area-steps small { display: block; margin-top: 2px; color: #6880a4; font-size: 12px; line-height: 1.5; }
.area-steps .step-chevron { width: 15px; height: 15px; color: #46658c; }
.area-hint { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin: auto 0 0; padding-top: 11px; border-top: 1px solid #edf2f8; font-size: 12px; color: #5b769c; line-height: 1.8; }
.area-hint .app-icon { width: 17px; height: 17px; }
.shortcut-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px; }
.shortcut-grid button { display: flex; gap: 16px; align-items: center; text-align: left; min-height: 74px; box-sizing: border-box; padding: 12px 20px; border: 1px solid #f4f9ff; border-radius: 11px; background: #ffffffed; color: #50709b; }
.shortcut-grid button:hover { border-color: #bdd8ff; background: #f8fbff; }
.shortcut-icon { display: grid; place-items: center; flex: 0 0 46px; height: 46px; border-radius: 9px; color: #0066ff; background: linear-gradient(135deg, #f7fbff, #edf6ff); }
.shortcut-icon .app-icon { width: 27px; height: 27px; stroke-width: 1.8; }
.shortcut-copy { flex: 1; min-width: 0; }
.shortcut-grid strong { display: block; color: #09214b; font-size: 16px; line-height: 1.5; font-weight: 700; }
.shortcut-grid small { display: block; color: #6880a4; font-size: 13px; margin-top: 2px; line-height: 1.5; }
.shortcut-grid button > .app-icon:last-child { width: 18px; height: 18px; }
.workflow-header { background: #fff; border: 1px solid #e1e8f0; border-radius: 12px; padding: 0 22px; }
.workflow-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 14px 0; }
.workflow-location { display: flex; align-items: center; gap: 14px; }
.workflow-location > span { color: #b5c2d0; }
.workflow-location h2 { margin: 0; font-size: 15px; }
.workflow-back { display: flex; align-items: center; gap: 7px; border: 0; background: transparent; padding: 5px 0; color: #7087a0; font-size: 12px !important; }
.workflow-back:hover { color: #2368cb; }
.workflow-areas { display: flex; gap: 24px; border-bottom: 1px solid #e8edf4; }
.workflow-areas button { display: flex; align-items: center; gap: 8px; padding: 12px 2px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: #70849c; font-size: 13px; }
.workflow-areas button.active { border-bottom-color: #2368cb; color: #2368cb; font-weight: 600; }
.workflow-stages { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; padding: 13px 0; }
.workflow-stages button { border: 0; background: transparent; color: #617892; padding: 8px 13px; border-radius: 6px; font-size: 12px; }
.workflow-stages button.active { background: #ebf2ff; color: #2368cb; font-weight: 600; }
.workflow-stage-hint { margin-left: auto; color: #8a9aab; font-size: 11px; }
.workflow-context { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 13px 16px; background: #eff6ff; border-radius: 8px; color: #536e8d; font-size: 12px; }
.workflow-header .workflow-context { margin-bottom: 15px; }
.workflow-context > span { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; min-width: 0; }
.workflow-context strong { overflow-wrap: anywhere; }
.workflow-context small { color: #8393a5; }
@media(max-width: 1100px) { .business-workspace { margin: 18px; gap: 20px; } .workbench-welcome { padding: 24px; gap: 18px; } .workbench-areas, .shortcut-grid { gap: 12px; } .workbench-area { padding: 16px; } .area-heading h3 { font-size: 15px; } .shortcut-grid button { padding: 14px; } }
@media(max-width: 850px) { .workbench-welcome { grid-template-columns: minmax(0, 1fr); } .resume-card { padding: 18px; } .workbench-areas, .shortcut-grid { grid-template-columns: minmax(0, 1fr); } .area-description { min-height: 0; } .workflow-stage-hint { display: none; } }
@media(max-width: 600px) { .business-workspace { margin: 12px; } .workbench-welcome { padding: 20px; } .welcome-copy h1 { font-size: 24px; line-height: 1.4; } .workbench-section-title > span { display: none; } .workflow-header { padding: 0 12px; } .workflow-location { gap: 8px; } .workflow-areas { gap: 16px; overflow-x: auto; } .workflow-areas button { white-space: nowrap; font-size: 12px; } .workflow-context { flex-wrap: wrap; } }
@media(min-width: 851px) and (max-width: 1300px) {
  .workbench-welcome { grid-template-columns: minmax(0, 1fr) 285px; }
  .workbench-welcome::before { right: 250px; opacity: .55; }
  .welcome-copy h1 { font-size: 28px; }
  .welcome-copy > p { max-width: 380px; }
  .area-heading { min-height: 100px; gap: 12px; align-items: flex-start; }
  .area-icon { flex-basis: 48px; height: 48px; }
  .area-icon .app-icon { width: 28px; height: 28px; }
  .area-heading h3 { font-size: 18px; }
  .area-description { font-size: 12px; }
  .area-heading > .area-watermark { width: 36px; height: 36px; opacity: .3; }
  .area-steps button { gap: 8px; padding-inline: 8px; }
  .area-hint { font-size: 11px; }
}
@media(min-width: 1301px) and (max-width: 1550px) {
  .area-heading { min-height: 84px; }
}
@media(max-width: 850px) {
  .workbench-welcome { gap: 22px; }
  .workbench-welcome::before { right: 0; width: 70%; height: 230px; opacity: .35; }
  .welcome-copy { position: relative; }
  .resume-card { min-height: 0; }
  .resume-card p { max-width: none; }
  .workbench-area { min-height: 0; }
  .area-heading { min-height: 70px; }
  .area-heading h3 { font-size: 20px; }
  .area-hint { margin-top: 2px; }
}
@media(max-width: 600px) {
  .workbench-home { margin: 12px; gap: 20px; }
  .workbench-welcome { padding: 20px; }
  .workbench-welcome::before { width: 100%; opacity: .2; }
  .workflow-eyebrow { font-size: 12px; }
  .welcome-copy h1 { font-size: 24px; }
  .welcome-copy > p { font-size: 13px; }
  .business-workspace .welcome-guide { gap: 8px; width: 100%; padding: 12px 10px; font-size: 12px; letter-spacing: 0; }
  .resume-card { padding: 18px; }
  .resume-card > strong { font-size: 18px; }
  .resume-card p { font-size: 13px; }
  .resume-decoration { right: 14px; width: 36px; height: 36px; }
  .resume-empty { font-size: 11px; }
  .workbench-section-title h2 { font-size: 20px; }
  .workbench-section-title p { font-size: 12px; }
  .area-heading { gap: 12px; }
  .area-icon { flex-basis: 48px; height: 52px; }
  .area-icon .app-icon { width: 29px; height: 29px; }
  .area-heading h3 { font-size: 18px; }
  .area-description { font-size: 12px; }
  .area-heading > .area-watermark { width: 36px; height: 36px; opacity: .35; }
  .area-steps button { gap: 10px; padding-inline: 9px; }
  .area-hint { font-size: 11px; }
}
</style>
