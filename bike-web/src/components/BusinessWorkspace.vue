<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { hasAnyPermission, hasPermission, hasRole, type PermissionContext } from '../permissions'
import ProductionManager from './ProductionManager.vue'
import SkuInventoryPanel from './SkuInventoryPanel.vue'
import FulfillmentQueue from './FulfillmentQueue.vue'
import PiWorkspace from './PiWorkspace.vue'
import ProductionFulfillmentGuide from './ProductionFulfillmentGuide.vue'

const props = defineProps<{ context: PermissionContext | null; username: string; refreshKey: number }>()
const emit = defineEmits<{ navigate: [key: string]; convert: [lockNo: string]; order: [orderNo: string] }>()
type SkuContext = { id:number; skuCode:string; modelId:number }
function readSkuContext(): SkuContext | undefined {
  const query = new URLSearchParams(location.search)
  const id = Number(query.get('skuId')), modelId = Number(query.get('modelId')), skuCode = query.get('skuCode')
  return Number.isSafeInteger(id) && id > 0 && Number.isSafeInteger(modelId) && modelId > 0 && skuCode ? { id, modelId, skuCode } : undefined
}
const selectedSku = ref<SkuContext | undefined>(readSkuContext())
const guideVisible = ref(false)
const internal = computed(() => !hasRole(props.context, 'WEB_AGENT') && !hasRole(props.context, 'PDA_OPERATOR'))
const productionRead = computed(() => !hasRole(props.context, 'WEB_AGENT') && hasAnyPermission(props.context,['ASSEMBLY_OPERATE','REWORK_OPERATE','VEHICLE_READ','PRODUCTION_MANAGE','STOCK_IN_OPERATE','STOCK_OUT_OPERATE']))
const steps = computed(() => [
  { key:'skus', title:'商品准备', detail:'规格与标签', allowed:productionRead.value },
  { key:'tasks', title:'生产办理', detail:'工单 · 四码 · 打印 · 入库', allowed:productionRead.value },
  { key:'prints', title:'打印队列', detail:'标签与补打', allowed:productionRead.value },
  { key:'inventory', title:'库存与锁定', detail:'核对库存 · 为客户锁定', allowed:!hasRole(props.context,'PDA_OPERATOR') && hasAnyPermission(props.context,['INVENTORY_READ','AGENT_INVENTORY_READ']) },
  { key:'locks', title:'锁定转订单', detail:'原地转单 · 保留占用', allowed:internal.value && hasAnyPermission(props.context,['AGENT_INVENTORY_READ','AGENT_LOCK_WRITE']) },
  { key:'orders', title:'订单与出库', detail:'确认 · 发货 · 出库明细', allowed:hasPermission(props.context,'ORDER_READ') },
  { key:'pis', title:'PI 与履约', detail:'收款 · 放行 · 交付', allowed:hasPermission(props.context,'PI_READ') && !hasRole(props.context,'PDA_OPERATOR') },
].filter(step => step.allowed))
const stage = ref(new URLSearchParams(location.search).get('stage') || 'tasks')
const currentStep = computed(() => steps.value.find(step => step.key === stage.value))
function syncStage() { stage.value = new URLSearchParams(location.search).get('stage') || steps.value[0]?.key || ''; selectedSku.value = readSkuContext() }
function choose(key: string) {
  if (!steps.value.some(step => step.key === key)) return
  stage.value = key
  const url = new URL(location.href); url.searchParams.set('stage',key); history.replaceState(null,'',url)
}
function nextStage(key: string, sku?: SkuContext) { if (sku) selectedSku.value = sku; choose(key) }
function navigate(key: string) {
  const targets: Record<string,string> = { 'production-tasks':'tasks','production-skus':'skus','production-prints':'prints','production-inventory':'inventory','sku-inventory':'inventory','inventory-locks':'locks', orders:'orders',pis:'pis' }
  if (targets[key] && steps.value.some(step => step.key === targets[key])) choose(targets[key]); else emit('navigate',key)
}
watch(steps, () => { if (!currentStep.value && steps.value.length) choose(steps.value[0]!.key) }, {immediate:true})
watch(selectedSku, sku => {
  const url = new URL(location.href)
  for (const key of ['skuId','modelId','skuCode']) url.searchParams.delete(key)
  if (sku) { url.searchParams.set('skuId',String(sku.id)); url.searchParams.set('modelId',String(sku.modelId)); url.searchParams.set('skuCode',sku.skuCode) }
  history.replaceState(null,'',url)
}, {immediate:true})
onMounted(() => window.addEventListener('popstate',syncStage))
onUnmounted(() => window.removeEventListener('popstate',syncStage))
</script>
<template>
  <div class="business-workspace">
    <section class="workflow-header"><div class="workflow-heading"><div><span class="workflow-eyebrow">从商品准备到出库交付</span><h2>业务办理工作台</h2><p>选一个环节开始，关联记录和下一步操作都留在这里。</p></div><div class="workflow-header-actions"><el-button type="primary" plain @click="guideVisible = true">生产到出库操作说明</el-button><span class="workflow-current">当前：{{ currentStep?.title || '暂无可用业务' }}</span></div></div>
      <nav class="workflow-stages" aria-label="业务办理环节"><button v-for="(step,index) in steps" :key="step.key" :class="{active:stage === step.key}" :aria-current="stage === step.key ? 'step' : undefined" @click="choose(step.key)"><span>{{ String(index+1).padStart(2,'0') }}</span><strong>{{ step.title }}</strong><small>{{ step.detail }}</small></button></nav>
      <div v-if="selectedSku" class="workflow-context"><span>当前商品：<strong>{{ selectedSku.skuCode }}</strong> · 已带入生产和库存环节</span><el-button link @click="selectedSku = undefined">清除商品范围</el-button></div>
    </section>
    <template v-if="currentStep">
    <ProductionManager v-if="['skus','tasks','prints'].includes(stage) && productionRead" :key="stage + ':' + (selectedSku?.id ?? '')" :view="stage" :username="username" :context="context" :initial-sku-id="selectedSku?.id" :initial-keyword="stage === 'skus' ? selectedSku?.skuCode : undefined" :can-manage="internal && hasPermission(context,'PRODUCTION_MANAGE')" :can-operate="hasPermission(context,'ASSEMBLY_OPERATE')" :can-report-issue="hasAnyPermission(context,['ASSEMBLY_OPERATE','REWORK_OPERATE'])" @navigate="navigate" @order="emit('order',$event)" @continue="nextStage" />
    <SkuInventoryPanel v-else-if="stage === 'inventory'" :key="selectedSku?.id ?? 'all'" :context="context" :initial-sku-id="selectedSku?.id" :initial-model-id="selectedSku?.modelId" @navigate="navigate" @convert="emit('convert',$event)" />
    <FulfillmentQueue v-else-if="stage === 'locks' || stage === 'orders'" :key="stage" :kind="stage" :context="context" :refresh-key="refreshKey" @convert="emit('convert',$event)" @order="emit('order',$event)" />
    <PiWorkspace v-else-if="stage === 'pis'" :context="context" @navigate="navigate" />
    </template>
    <ProductionFulfillmentGuide v-model="guideVisible" :context="context" initial-view="flow" @navigate="navigate" @stage="choose" />
  </div>
</template>
<style scoped>
.workflow-header-actions > .el-button{margin-left:0}
.business-workspace{min-width:0;display:grid;grid-template-columns:minmax(0,1fr);gap:20px;margin:18px}.business-workspace>:deep(.pi-workspace){width:100%;max-width:100%;box-sizing:border-box;margin:0;background:white;padding:20px}.business-workspace>:deep(.production-panel){min-width:0}.business-workspace :deep(.pi-heading){margin:0 0 18px;padding:12px 0}.business-workspace :deep(.pi-filters>*){max-width:100%}.workflow-header{padding:24px;background:linear-gradient(120deg,#f1f7ff,#fff);border:1px solid #dce6f1;border-radius:14px}.workflow-heading{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap}.workflow-header-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.workflow-eyebrow{font-size:12px;color:#3474af;letter-spacing:2px}.workflow-heading h2{margin:8px 0;color:#173a5b}.workflow-heading p{color:#6b7c8f}.workflow-current{color:#1767ad;background:#e7f1ff;border-radius:20px;padding:9px 16px;white-space:nowrap}.workflow-stages{display:grid;grid-template-columns:repeat(auto-fit,minmax(130px,1fr));gap:8px;margin-top:20px}.workflow-stages button{font:inherit;text-align:left;border:1px solid #dce6f1;border-radius:10px;background:#fff;padding:14px;cursor:pointer;min-width:0}.workflow-stages button>span{display:block;font-size:12px;color:#8da0b6;margin-bottom:12px}.workflow-stages strong{display:block;color:#284967;font-size:14px}.workflow-stages small{display:block;font-size:11px;color:#7b8b9c;line-height:1.7;margin-top:6px}.workflow-stages .active{background:#1767ad;border-color:#1767ad;box-shadow:0 6px 16px #1767ad20}.workflow-stages .active :is(span,strong,small){color:white}.workflow-context{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;border-top:1px solid #dde7f1;margin-top:18px;padding-top:14px;color:#48647e;font-size:13px}
@media(max-width:600px){.business-workspace{margin:10px}.business-workspace>:deep(.pi-workspace){padding:16px}.workflow-header{padding:16px}.workflow-heading{display:block}.workflow-header-actions{margin-top:12px}.workflow-current{display:inline-block;font-size:12px;padding:7px 12px}.workflow-stages{display:flex;overflow-x:auto;scroll-snap-type:x proximity;padding-bottom:8px}.workflow-stages button{flex:0 0 145px;scroll-snap-align:start}.workflow-stages button>span{margin-bottom:6px}.workflow-heading h2{font-size:22px}.workflow-heading p{font-size:13px;line-height:1.7}}
</style>
