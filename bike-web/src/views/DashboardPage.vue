<script setup lang="ts">
import { tr, trInline } from '../i18n/language'
import RecordIdColumn from '../components/table/RecordIdColumn.vue'
import { computed, onBeforeUnmount, ref, toRef, watch } from 'vue'
import api from '../api'
import type { PermissionContext } from '../permissions'
import type { Metric, ProcessStep, Tone } from '../config/page-presentation'
import { useProductionDashboard, type DashboardTask } from '../composables/useProductionDashboard'
import DashboardHero from '../components/dashboard/DashboardHero.vue'
import MetricGrid from '../components/dashboard/MetricGrid.vue'
import SectionHeader from '../components/common/SectionHeader.vue'
import AlertCard from '../components/common/AlertCard.vue'
import EmptyState from '../components/common/EmptyState.vue'
import ChartCard from '../components/charts/ChartCard.vue'
import { donutOption } from '../components/charts/chart-options'
import type { TrendPoint } from '../components/charts/TrendCard.vue'
import ProcessFlow from '../components/business/ProcessFlow.vue'
import ProgressBar from '../components/business/ProgressBar.vue'
import StatusTag from '../components/business/StatusTag.vue'
import DataTable from '../components/table/DataTable.vue'
import AppIcon from '../components/AppIcon.vue'

const props = defineProps<{
  metrics: { label: string; value: string; unit: string; trend: string; tone: string }[]
  statusRows: { label: string; count: number; color: string }[]
  points: TrendPoint[]
  operations: { time: string; action: string; target: string; operator: string; status: string }[]
  actions: { key: string; label: string; caption: string; icon: string }[]
  allowedViews: string[]
  context: PermissionContext | null
  loading: boolean
  error: string
}>()
const emit = defineEmits<{ navigate: [key: string]; refresh: [] }>()
const clock = ref(new Date())
const today = computed(() => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(clock.value))
const timer = window.setInterval(() => { clock.value = new Date() }, 60000)
const { tasks, total, failures, failureTotal, loading: productionLoading, taskError, failureError, permitted, refresh: refreshProduction } = useProductionDashboard(toRef(props, 'context'), today)
const taskFilter = ref('ALL'), trendMode = ref<'assembled' | 'stockIn' | 'stockOut'>('assembled')
const failureDrawer = ref(false), detailVisible = ref(false), detailLoading = ref(false), detailError = ref(''), selectedTask = ref<DashboardTask | null>(null)
let detailVersion = 0
onBeforeUnmount(() => { clearInterval(timer); detailVersion++ })
watch(detailVisible, visible => { if (!visible) detailVersion++ })
const sampleScope = computed(() => !permitted.value ? '无生产读取权限' : taskError.value ? '任务读取失败' : total.value === null ? '正在读取今日任务' : total.value > tasks.value.length ? tr('今日前 {count} 条 / 共 {total} 条', { count: tasks.value.length, total: total.value }) : tr('今日 {total} 条任务', { total: total.value }))
const codeFields = [
  { key: 'frameSn', label: '车架码', icon: 'vehicles' }, { key: 'motorSn', label: '电机码', icon: 'production' },
  { key: 'batterySn', label: '电池码', icon: 'battery' }, { key: 'displaySn', label: '仪表码', icon: 'display' },
] as const
const states = [{ key: 'OPEN', label: '作业中', color: '#b44e36' }, { key: 'COMPLETED', label: '已完成', color: '#6a9d7e' }, { key: 'CANCELLED', label: '已取消', color: '#b7a899' }]
const stateRows = computed(() => states.map(state => ({ ...state, count: tasks.value.filter(task => task.status === state.key).length })))
const filteredTasks = computed(() => tasks.value.filter(task => taskFilter.value === 'ALL' || task.status === taskFilter.value).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)))
const operationalTasks = computed(() => tasks.value.filter(task => task.status !== 'CANCELLED'))
const hasTaskData = computed(() => permitted.value && total.value !== null && !taskError.value)
const stations = computed(() => codeFields.map(field => {
  const scanned = operationalTasks.value.filter(task => Boolean(task[field.key])).length
  return { ...field, scanned, waiting: operationalTasks.value.length - scanned, percentage: operationalTasks.value.length ? Math.round(scanned / operationalTasks.value.length * 100) : 0 }
}))
function metric(label: string) { return props.error || props.loading ? null : props.metrics.find(item => item.label === label)?.value ?? null }
const primaryMetrics = computed<Metric[]>(() => [
  { label: '今日组装', value: metric('今日组装'), unit: '台', icon: 'inventory', tone: 'blue', hint: '今日完成组装 · 实时查询', sparkline: props.error || props.loading ? [] : props.points.map(point => point.assembled) },
  { label: '当前物理库存', value: metric('当前物理库存'), unit: '台', icon: 'layers', tone: 'green', hint: '全部仓库 · 在库整车' },
  { label: '今日入库', value: metric('今日入库'), unit: '台', icon: 'download', tone: 'orange', hint: '今日已完成入库', sparkline: props.error || props.loading ? [] : props.points.map(point => point.stockIn) },
  { label: '失败扫码记录', value: failureTotal.value, unit: '条', icon: 'warning', tone: 'red', hint: failureError.value ? '读取失败，请重试' : permitted.value ? '累计失败记录 · 非待处理任务数' : '当前账号无生产读取权限' },
])
const flow = computed<ProcessStep[]>(() => [
  { label: '生产任务', icon: 'tasks', count: hasTaskData.value ? tasks.value.length : null, description: sampleScope.value },
  ...codeFields.map(field => ({ label: field.label, icon: field.icon, count: hasTaskData.value ? operationalTasks.value.filter(task => Boolean(task[field.key])).length : null, description: '有效任务已扫码' })),
  { label: '聚合码', icon: 'trace', count: hasTaskData.value ? operationalTasks.value.filter(task => Boolean(task.qrCode)).length : null, description: '有效任务已生成' },
  { label: '质检', icon: 'roles', description: '暂无质检节点统计' },
  { label: '入库', icon: 'inventory', count: metric('今日入库') === null ? null : Number(metric('今日入库')?.replace(/,/g, '')), description: '今日全局 · 独立口径' },
])
const taskChart = computed(() => hasTaskData.value && tasks.value.length ? {
  tooltip: { trigger: 'item', renderMode: 'richText' },
  series: [{ type: 'pie', radius: ['63%', '86%'], center: ['50%', '50%'], label: { show: true, position: 'center', formatter: `${tasks.value.length}\n${tr('今日样本任务')}`, color: '#4b352d', fontSize: 17, lineHeight: 26 }, emphasis: { scale: false }, data: stateRows.value.map(row => ({ name: tr(row.label), value: row.count, itemStyle: { color: row.color } })) }],
} : null)
const trendOption = computed(() => props.error || !props.points.length ? null : {
  tooltip: { trigger: 'axis' }, grid: { top: 16, right: 14, bottom: 26, left: 32 },
  xAxis: { type: 'category', boundaryGap: false, data: props.points.map(point => point.date.slice(5).replace('-', '/')), axisTick: { show: false }, axisLine: { lineStyle: { color: '#e6d6c4' } }, axisLabel: { color: '#7b6153', fontSize: 12 } },
  yAxis: { type: 'value', minInterval: 1, axisLabel: { color: '#7b6153' }, splitLine: { lineStyle: { color: '#f4e8db' } } },
  series: [{ name: tr({ assembled: '组装', stockIn: '入库', stockOut: '出库' }[trendMode.value]), type: 'line', smooth: true, symbol: 'circle', symbolSize: 7, itemStyle: { color: '#b44e36', borderColor: '#fff', borderWidth: 1.5 }, lineStyle: { width: 2 }, data: props.points.map(point => point[trendMode.value]), areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: '#edba9d77' }, { offset: 1, color: '#fff1e222' }] } } }],
})
const supplementaryMetrics = computed<Metric[]>(() => props.metrics.map(item => ({ ...item, tone: item.tone as Tone, hint: '运营汇总 · 原统计口径' })))
const vehicleChart = computed(() => props.error ? null : donutOption(props.statusRows.map(row => ({ name: tr(row.label), value: row.count })), tr('整车总量')))
const allowedActions = computed(() => props.actions.filter(action => props.allowedViews.includes(action.key)))
function navigate(key: string) { if (props.allowedViews.includes(key)) emit('navigate', key) }
function refresh() { clock.value = new Date(); emit('refresh'); void refreshProduction() }
function progress(task: DashboardTask) { return codeFields.filter(field => Boolean(task[field.key])).length * 25 }
function dateTime(value: string) { return value.replace('T', ' ').slice(5, 16) }
async function showTask(id: string) {
  if (!permitted.value) return
  const current = ++detailVersion
  detailVisible.value = true; detailLoading.value = true; detailError.value = ''; selectedTask.value = null
  try {
    const response = await api.get(`/api/production/tasks/${encodeURIComponent(id)}`)
    if (current === detailVersion) selectedTask.value = response.data.data
  } catch (error) {
    if (current === detailVersion) detailError.value = api.isAxiosError(error) ? error.response?.data?.message ?? '任务详情加载失败' : '任务详情加载失败'
  } finally { if (current === detailVersion) detailLoading.value = false }
}
</script>

<template>
  <DashboardHero :date="today" :loading="loading" :error="error" />
  <div class="sm-page cc-dashboard">
    <AlertCard v-if="error" tone="error" :title='tr("运营数据加载失败")' :description="tr(error)"><template #action><el-button size="small" @click="refresh">{{ tr("重试") }}</el-button></template></AlertCard>
    <AlertCard v-if="taskError || failureError" tone="error" :title='tr("部分生产数据未能加载")' :description="tr(taskError || failureError)"><template #action><el-button size="small" @click="refreshProduction">{{ tr("重试生产数据") }}</el-button></template></AlertCard>
    <MetricGrid class="cc-primary-metrics" :items="primaryMetrics" />
    <section class="sm-card cc-flow"><SectionHeader :title='tr("生产全流程")' :description='tr("从生产任务到整车入库，全流程数字化管控")'><el-button size="small" :loading="loading || productionLoading" @click="refresh"><AppIcon name="refresh" />{{ tr("刷新数据") }}</el-button><button v-if="allowedViews.includes('production')" class="sm-text-link" @click="navigate('production')">{{ tr("查看详情") }} <AppIcon name="arrow" /></button></SectionHeader><ProcessFlow :steps="flow" illustrated :label='tr("今日生产流程快照")' /></section>
    <div class="cc-middle-grid">
      <section class="sm-card cc-stations"><SectionHeader :title='tr("工位扫码概览")' :description="`${sampleScope} · ${tr('排除已取消任务')}`" />
        <div class="cc-station-grid"><article v-for="(station, index) in stations" :key="station.key" class="cc-station-card">
          <header><strong>{{ tr(station.label + '工位') }}</strong><span>{{ String(index + 1).padStart(2, '0') }}</span></header>
          <ProgressBar :value="hasTaskData ? station.percentage : null" />
          <dl><div><dt><AppIcon name="check" />{{ tr("已扫码") }}</dt><dd>{{ hasTaskData ? station.scanned : '—' }} <small>/ {{ hasTaskData ? operationalTasks.length : '—' }}</small></dd></div><div><dt><AppIcon name="clock" />{{ tr("待扫码") }}</dt><dd>{{ hasTaskData ? station.waiting : '—' }}</dd></div><div><dt><AppIcon name="production" />{{ tr("节拍 / 直通率") }}</dt><dd class="cc-missing">{{ tr("未提供") }}</dd></div></dl>
        </article></div>
      </section>
      <ChartCard class="cc-task-state" :title='tr("生产任务状态")' :description="sampleScope" :option="taskChart" :loading="productionLoading" :empty-title="tr(taskError ? '任务统计加载失败' : !permitted ? '暂无生产读取权限' : '今日暂无生产任务')" :empty-description="tr(taskError || '按今日创建任务查询，不代表全系统任务总量。')">
        <div v-if="taskChart" class="cc-state-legend"><div v-for="state in stateRows" :key="state.key"><span><i :style="{ background: state.color }" />{{ tr(state.label) }}</span><strong>{{ state.count }}</strong><small>{{ Math.round(state.count / tasks.length * 100) }}%</small></div></div>
      </ChartCard>
    </div>
    <div class="cc-lower-grid">
      <ChartCard class="cc-trend" :title='tr("近 7 日生产趋势")' :description='tr("已完成记录 · 台")' :option="trendOption" :loading="loading" :empty-title="tr(error ? '趋势加载失败' : '暂无生产趋势')" :empty-description="tr(error || '近 7 日暂无可查询记录。')"><template #action><div class="cc-segmented" :aria-label='tr("趋势类型")'><button v-for="item in [{ key: 'assembled', label: '组装' }, { key: 'stockIn', label: '入库' }, { key: 'stockOut', label: '出库' }] as const" :key="item.key" :aria-pressed="trendMode === item.key" :class="{ active: trendMode === item.key }" @click="trendMode = item.key">{{ tr(item.label) }}</button></div></template></ChartCard>
      <section class="sm-card cc-today-tasks"><SectionHeader :title='tr("今日生产任务")'><button v-if="allowedViews.includes('production')" class="sm-text-link" @click="navigate('production')">{{ tr("更多任务") }} <AppIcon name="arrow" /></button></SectionHeader>
        <div class="cc-task-tabs" :aria-label='tr("任务状态")'><button :class="{ active: taskFilter === 'ALL' }" :aria-pressed="taskFilter === 'ALL'" @click="taskFilter = 'ALL'">{{ tr("全部 (") }}{{ tasks.length }})</button><button v-for="state in stateRows" :key="state.key" :class="{ active: taskFilter === state.key }" :aria-pressed="taskFilter === state.key" @click="taskFilter = state.key">{{ tr(state.label) }} ({{ state.count }})</button><span>{{ sampleScope }}</span></div>
        <DataTable :rows="filteredTasks" :loading="productionLoading" :error="taskError" :max-height="190" :empty-title="tr(!permitted ? '暂无生产读取权限' : '当前状态下暂无今日任务')" :empty-description='tr("可以切换状态或前往生产中心查询其它日期。")' row-key="id">
          <el-table-column prop="id" label="ID" min-width="172" show-overflow-tooltip />
          <el-table-column prop="skuCode" :label='tr("产品规格 (SKU)")' min-width="158" show-overflow-tooltip />
          <el-table-column prop="batchNo" :label='tr("批次")' min-width="125" show-overflow-tooltip />
          <el-table-column :label='tr("四码进度")' width="128"><template #default="{ row }"><ProgressBar :value="progress(row)" /></template></el-table-column>
          <el-table-column :label='tr("状态")' width="92"><template #default="{ row }"><StatusTag :label="tr(states.find(state => state.key === row.status)?.label ?? row.status)" /></template></el-table-column>
          <el-table-column :label='tr("开始时间")' width="130"><template #default="{ row }">{{ dateTime(row.createdAt) }}</template></el-table-column>
          <el-table-column :label='tr("操作")' fixed="right" width="62"><template #default="{ row }"><button class="sm-text-link" @click="showTask(row.id)">{{ tr("详情") }}</button></template></el-table-column>
        </DataTable>
      </section>
    </div>
    <section class="sm-card cc-failure-strip"><div class="cc-failure-heading"><strong>{{ tr("异常提醒") }} <b>{{ failureTotal ?? '—' }}</b></strong><small>{{ tr("失败扫码记录 · 非待处理任务") }}</small></div><div v-if="failures.length" class="cc-failure-cards"><button v-for="failure in failures.slice(0, 3)" :key="failure.id" @click="showTask(failure.taskId)"><AppIcon name="warning" /><span><strong>{{ failure.reason }}</strong><small :title="failure.taskId">{{ failure.taskId }}</small></span><time>{{ dateTime(failure.createdAt) }}</time></button></div><span v-else class="sm-caption">{{ tr(failureError || (!permitted ? '当前账号无生产读取权限' : productionLoading ? '正在读取…' : '暂无失败扫码记录')) }}</span><button v-if="permitted && !failureError" class="sm-text-link" @click="failureDrawer = true">{{ tr("查看记录") }} <AppIcon name="arrow" /></button></section>
    <el-collapse class="cc-supplement"><el-collapse-item name="original-dashboard" :title='tr("更多运营数据 · 库存与整车概览 / 快捷操作 / 最近操作")'>
      <MetricGrid :items="supplementaryMetrics" :loading="loading" />
      <div class="cc-supplement-grid"><ChartCard :title='tr("整车状态分布")' :description='tr("全部可见整车 · 原看板口径")' :option="vehicleChart" :loading="loading"><template #action><button v-if="allowedViews.includes('vehicles')" class="sm-text-link" @click="navigate('vehicles')">{{ tr("查看档案") }}</button></template></ChartCard><section class="sm-card"><SectionHeader :title='tr("快捷操作")' :description='tr("常用业务入口")' /><div class="cc-quick-actions"><button v-for="action in allowedActions" :key="action.key" @click="navigate(action.key)"><AppIcon :name="action.key" /><span><strong>{{ tr(action.label) }}</strong><small>{{ tr(action.caption) }}</small></span><AppIcon name="arrow" /></button></div></section></div>
      <section class="sm-card"><SectionHeader :title='tr("最近操作")' :description='tr("关键业务操作记录")'><button v-if="allowedViews.includes('trace')" class="sm-text-link" @click="navigate('trace')">{{ tr("查看全部") }}</button></SectionHeader><DataTable :rows="operations" :loading="loading" :error="error" :empty-title='tr("暂无操作记录")'><el-table-column prop="time" :label='tr("时间")' width="100" /><el-table-column prop="action" :label='tr("操作")' min-width="140" /><el-table-column prop="target" :label='tr("业务对象")' min-width="200" /><el-table-column prop="operator" :label='tr("操作人")' width="100" /><el-table-column prop="status" :label='tr("状态")' width="110" /></DataTable></section>
    </el-collapse-item></el-collapse>
  </div>
  <el-drawer v-model="detailVisible" :title='tr("生产任务详情")' size="min(650px, 95vw)"><EmptyState v-if="detailLoading || detailError" :loading="detailLoading" :error="!!detailError" :title="tr(detailError ? tr('任务详情加载失败') : tr('正在读取任务'))" :description="tr(detailError)" /><template v-else-if="selectedTask"><el-descriptions :column="1" border><el-descriptions-item v-for="field in [{ key: 'id', label: '任务号' }, { key: 'productName', label: '产品' }, { key: 'skuCode', label: 'SKU' }, { key: 'batchNo', label: '批次' }, { key: 'status', label: '状态' }, { key: 'qrCode', label: '聚合码' }, ...codeFields.map(field => ({ key: field.key, label: field.label })), { key: 'createdBy', label: '创建人' }, { key: 'createdAt', label: '创建时间' }, { key: 'updatedAt', label: '更新时间' }]" :key="field.key" :label="tr(field.label)">{{ selectedTask[field.key as keyof DashboardTask] || '—' }}</el-descriptions-item></el-descriptions><div class="cc-detail-scans"><SectionHeader :title='tr("扫码记录")' /><DataTable :rows="selectedTask.scans ?? []" :empty-title='tr("尚未扫码")'><el-table-column prop="codeType" :label='tr("类型")' width="100" /><el-table-column prop="code" :label='tr("编码")' min-width="170" /><el-table-column prop="operatorName" :label='tr("操作人")' width="90" /><el-table-column prop="scannedAt" :label='tr("扫码时间")' min-width="160" /></DataTable></div></template></el-drawer>
  <el-drawer v-model="failureDrawer" :title='tr("失败扫码记录")' size="min(1000px, 95vw)"><p class="sm-caption">{{ trInline("累计") }} {{ failureTotal ?? '—' }} {{ trInline("条，展示最新") }} {{ failures.length }} {{ trInline("条。此记录没有处理状态，不代表尚未解决。") }}</p><DataTable :rows="failures" :error="failureError" :empty-title='tr("暂无失败扫码记录")'><RecordIdColumn /><el-table-column prop="taskId" :label='tr("任务号")' min-width="200" /><el-table-column prop="code" :label='tr("扫描编码")' min-width="220" /><el-table-column prop="reason" :label='tr("失败原因")' min-width="180" /><el-table-column prop="errorCode" :label='tr("错误码")' min-width="150" /><el-table-column prop="operatorName" :label='tr("操作人")' width="90" /><el-table-column prop="createdAt" :label='tr("发生时间")' min-width="170" /></DataTable></el-drawer>
</template>
