<script setup lang="ts">
import { tr } from '../i18n/language'
import { computed, defineAsyncComponent, ref, toRef, watch } from 'vue'
import { peekSalesIntent, salesIntentState } from '../sales-routing'
import type { ModuleData } from '../config/page-presentation'
import type { PermissionContext } from '../permissions'
import { hasPermission, hasRole } from '../permissions'
import { useOperationalOverview } from '../composables/useOperationalOverview'
import AssemblyProgressPage from './AssemblyProgressPage.vue'
import VehicleArchivePage from './VehicleArchivePage.vue'
import InventoryPage from './InventoryPage.vue'
import StockInPage from './StockInPage.vue'
import StockOutPage from './StockOutPage.vue'
import StockFlowPage from './StockFlowPage.vue'
import SalesOrdersPage from './SalesOrdersPage.vue'
import AlertCard from '../components/common/AlertCard.vue'
import MetricGrid from '../components/dashboard/MetricGrid.vue'
import ModuleList from '../components/business/ModuleList.vue'
const props = defineProps<{ moduleKey: string; module: ModuleData; title: string; search: string; page: number; pageSize: number; loading: boolean; error: string; context: PermissionContext | null; columnWidth: (column: string) => number }>()
const PiWorkspace = defineAsyncComponent(() => import('../components/PiWorkspace.vue'))
const salesMode = ref('DOMESTIC')
// 锁定转单或订单办理选择“外销 · PI”后跳转到此，切换到外销页并由 PI 工作台自动打开对应新建流程。
const exportIntent = computed(() => props.moduleKey === 'orders' ? peekSalesIntent('EXPORT') : null)
watch(() => salesIntentState.intent, () => { if (exportIntent.value) salesMode.value = 'EXPORT' }, { immediate: true })
const canUseExportPi = computed(() => props.moduleKey === 'orders' && hasPermission(props.context, 'PI_READ')
  && !hasRole(props.context, 'WEB_AGENT') && !hasRole(props.context, 'PDA_OPERATOR'))
const showExportPi = computed(() => canUseExportPi.value && salesMode.value === 'EXPORT')
const emit = defineEmits<{ 'update:search': [value: string]; 'update:page': [value: number]; 'update:pageSize': [value: number]; refresh: []; reset: []; pageChange: []; sizeChange: [] }>()
const overview = useOperationalOverview(toRef(props, 'context'), toRef(props, 'moduleKey'))
const { points, metrics, permitted, error: overviewError, loading: overviewLoading } = overview
const listTitle = computed(() => ({ assembly: '组装任务列表', vehicles: '整车档案列表', inventory: '库存明细列表', 'stock-in': '入库记录列表', 'stock-out': '出库记录列表', stock: '出入库记录列表', orders: '销售订单列表' }[props.moduleKey] ?? tr('{title}列表', { title: tr(props.title) })))
function refresh() { emit('refresh'); void overview.refresh() }
</script>
<template><div class="sm-page">
  <el-radio-group v-if="canUseExportPi" v-model="salesMode" :aria-label='tr("国内销售类型")' class="sales-scenario-tabs">
    <el-radio-button value="DOMESTIC">{{ tr("内销") }}</el-radio-button>
    <el-radio-button value="EXPORT">{{ tr("外销") }}</el-radio-button>
  </el-radio-group>
  <PiWorkspace v-if="showExportPi" :context="context" sales-scenario="CHINA_EXPORT" :intent="exportIntent" />
  <div v-show="!showExportPi" class="sm-page">
  <el-alert v-if="moduleKey === 'orders'" :title='tr("国内内销办理购销合同")' :description='tr("列表显示内销订单和尚未选择销售类型的历史订单。进入订单办理：第一步先生成并核对「10.23金轮信德-广州塔威 购销合同」，再确认订单。外销订单请切换到“外销”办理 PI。")' type="info" :closable="false" />
  <AlertCard v-if="error" tone="error" :title='tr("业务数据加载失败")' :description="tr(error)"><template #action><el-button size="small" @click="refresh">{{ tr("重试") }}</el-button></template></AlertCard>
  <AlertCard v-if="overviewError" tone="error" :title='tr("统计数据加载失败")' :description="tr(overviewError)"><template #action><el-button size="small" @click="overview.refresh">{{ tr("重试统计") }}</el-button></template></AlertCard>
  <AssemblyProgressPage v-if="moduleKey === 'assembly'" :module="module" :loading="loading" :error="error" :today-assembled="overview.value('今日组装')" />
  <VehicleArchivePage v-else-if="moduleKey === 'vehicles'" :module="module" :loading="loading" :error="error" />
  <InventoryPage v-else-if="moduleKey === 'inventory'" :module="module" :loading="loading" :error="error" :summary="metrics" />
  <StockInPage v-else-if="moduleKey === 'stock-in'" :points="points" :loading="overviewLoading" :error="overviewError" :permitted="permitted" />
  <StockOutPage v-else-if="moduleKey === 'stock-out'" :points="points" :loading="overviewLoading" :error="overviewError" :permitted="permitted" />
  <StockFlowPage v-else-if="moduleKey === 'stock'" :points="points" :loading="overviewLoading" :error="overviewError" :permitted="permitted" />
  <SalesOrdersPage v-else-if="moduleKey === 'orders'" :module="module" :loading="loading" :error="error" />
  <MetricGrid v-else :items="module.stats.map(stat => ({ label: stat.label, value: stat.value }))" :loading="loading" />
  <slot name="before-list" />
  <ModuleList :module="module" :title="listTitle" :search="search" :page="page" :page-size="pageSize" :loading="loading" :error="error" :column-width="columnWidth" @update:search="emit('update:search', $event)" @update:page="emit('update:page', $event)" @update:page-size="emit('update:pageSize', $event)" @refresh="refresh" @reset="emit('reset')" @page-change="emit('pageChange')" @size-change="emit('sizeChange')"><template #actions><slot name="actions" /></template><template #row-actions="{ row }"><slot name="row-actions" :row="row" /></template></ModuleList>
</div></div></template>
<style scoped>.sales-scenario-tabs{display:flex;flex-wrap:wrap;gap:8px}</style>
