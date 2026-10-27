<script setup lang="ts">
import { computed } from 'vue'
import ChartCard from './ChartCard.vue'
export type TrendPoint = { date: string; assembled: number; stockIn: number; stockOut: number }
const props = defineProps<{ title: string; points: TrendPoint[] | null; direction: 'in' | 'out' | 'both'; loading?: boolean; error?: string; permitted?: boolean }>()
const option = computed(() => {
  if (!props.points?.length) return null
  const both = props.direction === 'both'
  const series = both ? [
    { name: '入库数量', type: 'bar', barMaxWidth: 17, data: props.points.map(p => p.stockIn), itemStyle: { color: '#1677ff', borderRadius: [2, 2, 0, 0] } },
    { name: '出库数量', type: 'bar', barMaxWidth: 17, data: props.points.map(p => p.stockOut), itemStyle: { color: '#16bfa3', borderRadius: [2, 2, 0, 0] } },
    { name: '净变化', type: 'line', symbolSize: 6, data: props.points.map(p => p.stockIn - p.stockOut), itemStyle: { color: '#1677ff' } },
  ] : [{ name: props.direction === 'in' ? '入库数量' : '出库数量', type: 'line', smooth: true, symbolSize: 6, data: props.points.map(p => props.direction === 'in' ? p.stockIn : p.stockOut), lineStyle: { width: 2 }, itemStyle: { color: '#1677ff', borderColor: '#fff', borderWidth: 1 }, areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: '#a4cdff99' }, { offset: 1, color: '#edf6ff22' }] } } }]
  return { tooltip: { trigger: 'axis' }, legend: { show: both, top: 0, textStyle: { color: '#7692af', fontSize: 10 }, itemWidth: 9, itemHeight: 9 }, grid: { top: both ? 32 : 15, right: 18, bottom: 25, left: 36 }, xAxis: { type: 'category', data: props.points.map(p => p.date.slice(5).replace('-', '/')), boundaryGap: both, axisTick: { show: false }, axisLine: { lineStyle: { color: '#dce8f7' } }, axisLabel: { color: '#7692af' } }, yAxis: { type: 'value', minInterval: 1, axisLabel: { color: '#7692af' }, splitLine: { lineStyle: { color: '#ecf2fa' } } }, series }
})
</script>
<template><ChartCard :title="title" description="已完成记录 · 近 7 日 · 不随列表搜索变化" :option="option" :loading="loading" :empty-title="error ? '趋势加载失败' : permitted === false ? '暂无趋势访问权限' : '暂无流转记录'" :empty-description="error || (permitted === false ? '当前账号可继续查询下方业务记录。' : '统计范围内暂无已完成的流转记录。')"><template #action><slot name="action" /></template><slot /></ChartCard></template>
