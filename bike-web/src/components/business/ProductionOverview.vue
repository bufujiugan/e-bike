<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Metric } from '../../config/page-presentation'
import { productionFlow } from '../../config/page-presentation'
import MetricGrid from '../dashboard/MetricGrid.vue'
import SectionHeader from '../common/SectionHeader.vue'
import EmptyState from '../common/EmptyState.vue'
import ProcessFlow from './ProcessFlow.vue'
import ProgressBar from './ProgressBar.vue'
import StatusTag from './StatusTag.vue'
import AppIcon from '../AppIcon.vue'
import ChartCard from '../charts/ChartCard.vue'
import DataTable from '../table/DataTable.vue'
type Task = { id: string | number; workOrderNo?: string; skuCode?: string; batchNo?: string; status: string; createdAt?: string; updatedAt?: string; plannedQuantity?: number; completedQuantity?: number; inProgressQuantity?: number; issueQuantity?: number }
const props = defineProps<{ tasks: Task[]; total: number; loading: boolean; error?: string; canCreate: boolean }>()
const emit = defineEmits<{ detail: [id: string | number]; create: []; refresh: []; more: []; issues: [] }>()
const plannedTotal = computed(() => props.tasks.reduce((sum, row) => sum + Number(row.plannedQuantity ?? 0), 0))
const completedTotal = computed(() => props.tasks.reduce((sum, row) => sum + Number(row.completedQuantity ?? 0), 0))
const inProgressTotal = computed(() => props.tasks.reduce((sum, row) => sum + Number(row.inProgressQuantity ?? 0), 0))
const issueTotal = computed(() => props.tasks.reduce((sum, row) => sum + Number(row.issueQuantity ?? 0), 0))
const metrics = computed<Metric[]>(() => [
  { label: '生产工单', value: props.error ? null : props.total, icon: 'tasks', hint: '当前筛选 · 全部分页结果' },
  { label: '计划数量', value: props.error ? null : plannedTotal.value, icon: 'production', hint: '当前页工单', tone: 'blue' },
  { label: '已完成车辆', value: props.error ? null : completedTotal.value, icon: 'check', hint: '当前页工单', tone: 'green' },
  { label: '开放异常', value: props.error ? null : issueTotal.value, icon: 'warning', hint: '当前页工单关联', tone: 'red' },
])
const steps = computed(() => productionFlow.map((step, index) => {
  const counts = [props.tasks.length, plannedTotal.value, inProgressTotal.value, completedTotal.value, completedTotal.value, completedTotal.value, null, null]
  const count = counts[index] ?? null
  return { ...step, count: props.error ? null : count, percent: null }
}))
// 保留接口的创建时间倒序，概览和完整列表使用同一顺序。
const currentTasks = computed(() => props.tasks.slice(0, 5))
function percent(task: Task) { return task.plannedQuantity ? Math.round(Number(task.completedQuantity ?? 0) * 100 / task.plannedQuantity) : 0 }
const formatDateTime = (value?: string | null) => value ? value.replace('T', ' ').slice(0, 19) : '—'
const stateRange = ref(0), trendDays = ref(7)
const ranges = [{ value: 0, label: '当前页' }, { value: 7, label: '近7天' }, { value: 30, label: '近30天' }]
function dateKey(offset = 0) {
  const shanghaiDate = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date())
  const date = new Date(`${shanghaiDate}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + offset)
  return date.toISOString().slice(0, 10)
}
function inRange(task: Task, days: number) {
  const date = task.createdAt?.slice(0, 10)
  return days === 0 || !!date && date >= dateKey(1 - days) && date <= dateKey()
}
const recentDays = computed(() => Array.from({ length: trendDays.value }, (_, index) => dateKey(index - trendDays.value + 1)))
const createdSeries = computed(() => recentDays.value.map(date => props.tasks.filter(task => task.createdAt?.startsWith(date)).length))
const completedSeries = computed(() => recentDays.value.map(date => props.tasks.filter(task => task.createdAt?.startsWith(date) && task.status === 'COMPLETED').length))
const displayMetrics = computed(() => metrics.value.map((metric, index) => ({ ...metric, sparkline: props.error || index === 3 ? [] : index === 0 ? createdSeries.value : index === 2 ? completedSeries.value : recentDays.value.map(date => props.tasks.filter(task => task.createdAt?.startsWith(date) && task.status === 'OPEN').length) })))
const displaySteps = computed(() => steps.value.map((step, index) => ({ ...step, icon: ['tasks', 'vehicles', 'production'][index], description: index >= 6 ? '—' : undefined })))
const stations = computed(() => [
  { key: 'planned', label: '计划数量', bound: plannedTotal.value, percent: plannedTotal.value ? 100 : 0 },
  { key: 'completed', label: '已完成', bound: completedTotal.value, percent: plannedTotal.value ? Math.round(completedTotal.value * 100 / plannedTotal.value) : 0 },
  { key: 'working', label: '作业中', bound: inProgressTotal.value, percent: plannedTotal.value ? Math.round(inProgressTotal.value * 100 / plannedTotal.value) : 0 },
  { key: 'issue', label: '异常记录', bound: issueTotal.value, percent: plannedTotal.value ? Math.round(issueTotal.value * 100 / plannedTotal.value) : 0 },
])
const states = [{ key: 'COMPLETED', label: '已完成', color: '#08bfa2' }, { key: 'RELEASED', label: '已下达', color: '#1682ff' }, { key: 'PAUSED', label: '已暂停', color: '#95accb' }]
const scopedTasks = computed(() => props.tasks.filter(task => inRange(task, stateRange.value)))
const stateRows = computed(() => states.map(state => ({ ...state, count: scopedTasks.value.filter(task => task.status === state.key).length })))
const stateChart = computed(() => props.error || !scopedTasks.value.length ? null : {
  tooltip: { trigger: 'item', renderMode: 'richText' },
  series: [{ type: 'pie', radius: ['64%', '87%'], center: ['50%', '50%'], label: { show: true, position: 'center', formatter: `${scopedTasks.value.length}\n样本工单`, fontSize: 16, lineHeight: 24, color: '#0b2956' }, emphasis: { scale: false }, data: stateRows.value.map(state => ({ name: state.label, value: state.count, itemStyle: { color: state.color } })) }],
})
const trendChart = computed(() => props.error || !props.tasks.some(task => inRange(task, trendDays.value)) ? null : {
  tooltip: { trigger: 'axis', renderMode: 'richText' },
  legend: { top: 0, right: 0, icon: 'circle', itemWidth: 7, itemHeight: 7, textStyle: { fontSize: 10, color: '#6f8eaf' } },
  grid: { top: 32, left: 30, right: 12, bottom: 24 },
  xAxis: { type: 'category', boundaryGap: false, data: recentDays.value.map(date => date.slice(5).replace('-', '/')), axisTick: { show: false }, axisLine: { lineStyle: { color: '#dceafa' } }, axisLabel: { color: '#688bb5', fontSize: 10 } },
  yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: '#ecf3fb' } }, axisLabel: { color: '#688bb5', fontSize: 10 } },
  series: [{ name: '创建工单', type: 'line', smooth: true, symbolSize: 5, data: createdSeries.value, areaStyle: { opacity: .08 }, lineStyle: { width: 2 } }, { name: '其中已完成', type: 'line', smooth: true, symbolSize: 5, data: completedSeries.value, areaStyle: { opacity: .07 }, lineStyle: { width: 2 } }],
})
function stateLabel(status: string) { return states.find(state => state.key === status)?.label ?? status }
</script>
<template>
  <div class="pc-overview">
    <MetricGrid :items="displayMetrics" :loading="loading" />
    <section class="sm-card pc-flow"><SectionHeader title="生产流程进度" description="当前页工单计划、作业中与完工数量；质检与入库暂无节点汇总"><span class="sm-scope-label">当前页 {{ tasks.length }} 条</span></SectionHeader><ProcessFlow :steps="displaySteps" illustrated /></section>
    <div class="pc-middle-grid">
      <section class="sm-card pc-current-tasks"><SectionHeader title="当前生产工单" description="当前页最多5条 · 创建时间倒序"><button class="sm-text-link" @click="emit('more')">更多工单 <AppIcon name="arrow" /></button></SectionHeader>
        <DataTable :rows="error ? [] : currentTasks" :loading="loading" :error="error" :max-height="208" row-key="id" empty-title="暂无生产工单" empty-description="当前查询未返回工单，可调整条件或创建工单。">
          <el-table-column prop="workOrderNo" label="工单号" min-width="120" show-overflow-tooltip />
          <el-table-column prop="skuCode" label="产品规格 (SKU)" min-width="95" show-overflow-tooltip />
          <el-table-column label="完工进度" min-width="90"><template #default="{ row }"><ProgressBar :value="percent(row)" /></template></el-table-column>
          <el-table-column label="状态" width="65"><template #default="{ row }"><StatusTag :label="stateLabel(row.status)" /></template></el-table-column>
          <el-table-column label="创建时间" min-width="165"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
          <el-table-column label="修改时间" min-width="165"><template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template></el-table-column>
          <el-table-column label="操作" width="45" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="emit('detail', String(row.id))">详情</el-button></template></el-table-column>
          <template #empty-action><el-button size="small" @click="emit('refresh')">刷新数据</el-button><el-button v-if="canCreate" type="primary" size="small" @click="emit('create')">创建工单</el-button></template>
        </DataTable>
      </section>
      <section class="sm-card pc-stations"><SectionHeader title="批量生产状态" description="当前页工单数量汇总"><button class="sm-text-link" @click="emit('more')" aria-label="查看生产工单明细"><AppIcon name="arrow" /></button></SectionHeader>
        <div class="pc-station-grid"><article v-for="(station, index) in stations" :key="station.key"><header><strong>{{ station.label }}</strong><span>0{{ index + 1 }}</span></header><ProgressBar :value="loading ? null : station.percent" /><footer><span><i />当前页</span><strong>{{ error || loading ? '—' : station.bound }} <small>/ {{ error || loading ? '—' : plannedTotal }}</small></strong></footer></article></div>
      </section>
      <ChartCard class="pc-state" title="生产工单统计" :option="stateChart" :loading="loading" :empty-title="error ? '工单统计加载失败' : '暂无工单统计'" :empty-description="error || '当前页所选日期范围没有工单。'"><template #action><div class="pc-segmented"><button v-for="range in ranges" :key="range.value" :aria-pressed="stateRange === range.value" @click="stateRange = range.value">{{ range.label }}</button></div></template><div v-if="stateChart && !loading" class="pc-state-legend"><div v-for="state in stateRows" :key="state.key"><span><i :style="{ background: state.color }" />{{ state.label }}</span><strong>{{ state.count }}</strong><small>{{ Math.round(state.count / scopedTasks.length * 100) }}%</small></div></div><p class="pc-chart-scope">当前页样本 · 按创建日期筛选</p></ChartCard>
    </div>
    <div class="pc-bottom-grid">
      <ChartCard class="pc-trend" title="生产趋势" description="当前页 · 按工单创建日期统计" :option="trendChart" :loading="loading" :empty-title="error ? '趋势加载失败' : '暂无趋势数据'" :empty-description="error || '当前页所选日期范围没有工单。'"><template #action><el-select v-model="trendDays" aria-label="生产趋势日期范围"><el-option :value="7" label="近7天" /><el-option :value="30" label="近30天" /></el-select></template></ChartCard>
      <section class="sm-card pc-exceptions"><SectionHeader title="工单异常" description="工单关联的开放异常汇总"><button class="sm-text-link" @click="emit('issues')">异常隔离 <AppIcon name="arrow" /></button></SectionHeader><div class="pc-exception-columns"><span>工单号</span><span>异常数量</span><span>产品规格 (SKU)</span><span>批次</span><span>计划数量</span><span>状态</span></div><EmptyState compact title="异常数量已纳入工单统计" description="进入生产工单列表查看异常数量，或进入异常隔离处理原始记录。" /></section>
    </div>
  </div>
</template>
