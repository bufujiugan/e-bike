<script setup lang="ts">
import { computed, toRef } from 'vue'
import type { ModuleData } from '../config/page-presentation'
import type { PermissionContext } from '../permissions'
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
const emit = defineEmits<{ 'update:search': [value: string]; 'update:page': [value: number]; 'update:pageSize': [value: number]; refresh: []; reset: []; pageChange: []; sizeChange: [] }>()
const overview = useOperationalOverview(toRef(props, 'context'), toRef(props, 'moduleKey'))
const { points, metrics, permitted, error: overviewError, loading: overviewLoading } = overview
const listTitle = computed(() => ({ assembly: '组装任务列表', vehicles: '整车档案列表', inventory: '库存明细列表', 'stock-in': '入库记录列表', 'stock-out': '出库记录列表', stock: '出入库记录列表', orders: '销售订单列表' }[props.moduleKey] ?? `${props.title}列表`))
function refresh() { emit('refresh'); void overview.refresh() }
</script>
<template><div class="sm-page">
  <AlertCard v-if="error" tone="error" title="业务数据加载失败" :description="error"><template #action><el-button size="small" @click="refresh">重试</el-button></template></AlertCard>
  <AlertCard v-if="overviewError" tone="error" title="统计数据加载失败" :description="overviewError"><template #action><el-button size="small" @click="overview.refresh">重试统计</el-button></template></AlertCard>
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
</div></template>
