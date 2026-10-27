<script setup lang="ts">
import { computed } from 'vue'
import type { ModuleData, Metric } from '../config/page-presentation'
import MetricGrid from '../components/dashboard/MetricGrid.vue'
import ChartCard from '../components/charts/ChartCard.vue'
import { barOption, donutOption } from '../components/charts/chart-options'
import { groupedColumn, sumColumn } from '../components/business/module-presentation'
import SectionHeader from '../components/common/SectionHeader.vue'
import AlertCard from '../components/common/AlertCard.vue'
import ProgressBar from '../components/business/ProgressBar.vue'
const props = defineProps<{ module: ModuleData; loading: boolean; error: string; summary: { label: string; value: number }[] | null }>()
function summaryValue(label: string) { return props.summary?.find(item => item.label === label)?.value ?? null }
const metrics = computed<Metric[]>(() => [
  { label: '库存总量', value: summaryValue('当前物理库存'), icon: 'inventory', hint: props.summary ? '运营汇总 · 不随列表筛选变化' : '暂无全量库存统计' },
  { label: '可新增承诺上限', value: summaryValue('车型可新增承诺上限'), icon: 'check', tone: 'green', hint: '车型口径 · 沿用库存规则' },
  { label: '锁定库存', value: summaryValue('当前锁定库存'), icon: 'lock', hint: '运营统计' },
  { label: '隔离库存', value: summaryValue('隔离库存'), icon: 'warning', tone: 'red', hint: '运营统计' },
  { label: '查询日期', value: props.module.stats.find(stat => stat.label === '查询日期')?.value ?? '—', icon: 'calendar', hint: '后端查询日期' },
])
const warehouses = computed(() => props.error ? null : barOption(groupedColumn(props.module, '仓库', '物理库存').slice(0, 6)))
const models = computed(() => props.error ? null : donutOption(groupedColumn(props.module, '车型', '物理库存'), '当前页物理库存'))
const physical = computed(() => sumColumn(props.module, '物理库存'))
const health = computed(() => ['物理库存', '隔离库存', '临时锁定', '订单占用', '库存缺口'].map(label => ({ label, value: props.error ? null : sumColumn(props.module, label), tone: label.includes('隔离') || label.includes('缺口') ? 'red' : label === '物理库存' ? 'green' : 'blue' })))
</script>
<template><MetricGrid :items="metrics" :loading="loading" /><div class="sm-chart-grid"><ChartCard title="仓库库存分布" description="当前页物理库存 · 最多 6 个仓库" :option="warehouses" :loading="loading" /><ChartCard title="车型库存结构" description="当前页物理库存" :option="models" :loading="loading" /><section class="sm-card"><SectionHeader title="库存健康度" description="当前页数量 · 各项并非互斥分类" /><div class="sm-health-list"><div v-for="item in health" :key="item.label" class="sm-health-row"><span>{{ item.label }}</span><strong>{{ item.value ?? '—' }}</strong><ProgressBar :value="item.value === null || !physical ? null : item.value * 100 / physical" :tone="item.tone" :show-label="false" /></div></div></section></div><AlertCard title="库存口径" :description="module.subtitle || '车型级数值不能跨库位累计；具体 SKU 上限请进入 SKU 库存锁定。'" /><slot /></template>
