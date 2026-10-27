<script setup lang="ts">
import { computed } from 'vue'
import type { Metric } from '../config/page-presentation'
import MetricGrid from '../components/dashboard/MetricGrid.vue'
import TrendCard, { type TrendPoint } from '../components/charts/TrendCard.vue'
import AppIcon from '../components/AppIcon.vue'
const props = defineProps<{ direction: 'in' | 'out' | 'both'; points: TrendPoint[] | null; loading: boolean; error: string; permitted: boolean }>()
const label = computed(() => props.direction === 'out' ? '出库' : '入库')
const today = computed(() => props.points?.[props.points.length - 1])
function total(field: 'stockIn' | 'stockOut') { return props.points ? props.points.reduce((sum, point) => sum + point[field], 0) : null }
const metrics = computed<Metric[]>(() => {
  if (props.direction === 'both') {
    const incoming = total('stockIn'), outgoing = total('stockOut')
    return [
      { label: '入库', value: incoming, icon: 'download', hint: '近 7 日已完成记录' },
      { label: '出库', value: outgoing, icon: 'upload', tone: 'green', hint: '近 7 日已完成记录' },
      { label: '净变化', value: incoming === null || outgoing === null ? null : `${incoming - outgoing >= 0 ? '+' : ''}${incoming - outgoing}`, icon: 'chart', hint: '近 7 日入库 − 出库' },
      { label: '异常', value: null, tone: 'red', icon: 'warning', hint: '暂无异常汇总' },
    ]
  }
  const field = props.direction === 'in' ? 'stockIn' : 'stockOut'
  return [
    { label: `今日${label.value}`, value: today.value?.[field] ?? null, icon: props.direction === 'in' ? 'download' : 'truck', hint: '已完成记录' },
    { label: `近 7 日${label.value}`, value: total(field), icon: 'inventory', tone: 'green', hint: '滚动 7 日 · 非自然周' },
    { label: `待${label.value}`, value: null, icon: 'clock', tone: 'orange', hint: '暂无待办汇总' },
    { label: `异常${label.value}`, value: null, icon: 'warning', tone: 'red', hint: '暂无异常汇总' },
  ]
})
</script>
<template><MetricGrid :items="metrics" :loading="loading" /><div :class="direction === 'out' ? 'sm-stock-out-grid' : ''"><TrendCard :title="direction === 'both' ? '库存流转趋势' : `近 7 日${label}趋势`" :points="points" :direction="direction" :loading="loading" :error="error" :permitted="permitted"><template #action><span class="sm-scope-label">近 7 日</span></template><div v-if="direction === 'both' && today" class="sm-today-strip"><strong>今日数据</strong><span>{{ today.date }}</span><span>入库 <b>{{ today.stockIn }}</b></span><span>出库 <b>{{ today.stockOut }}</b></span><span>净变化 <b>{{ today.stockIn - today.stockOut > 0 ? '+' : '' }}{{ today.stockIn - today.stockOut }}</b></span></div></TrendCard><section v-if="direction === 'out'" class="sm-card sm-logistics-art"><AppIcon name="inventory" /><strong>高效 · 准确 · 可追溯</strong><p>让每一辆车，准时出发</p></section></div><slot /></template>
