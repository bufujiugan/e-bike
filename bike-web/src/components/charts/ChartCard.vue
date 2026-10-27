<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch, nextTick } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import SectionHeader from '../common/SectionHeader.vue'
import EmptyState from '../common/EmptyState.vue'
echarts.use([BarChart, LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])
const props = withDefaults(defineProps<{ title: string; description?: string; option?: echarts.EChartsCoreOption | null; emptyTitle?: string; emptyDescription?: string; loading?: boolean }>(), { emptyTitle: '暂无统计数据', emptyDescription: '当前数据范围暂无可展示的统计。' })
const target = ref<HTMLElement>()
let chart: echarts.ECharts | undefined
let observer: ResizeObserver | undefined
async function render() {
  await nextTick()
  if (!target.value || !props.option) { chart?.dispose(); chart = undefined; return }
  chart ??= echarts.init(target.value)
  chart.setOption({ animation: false, color: ['#1677ff', '#16bfa3', '#7b61ff', '#ffb94f', '#37b9df'], textStyle: { fontFamily: 'Microsoft YaHei, sans-serif', fontSize: 11 }, ...props.option }, { notMerge: true })
  chart.resize()
}
watch(() => props.option, render, { deep: true })
onMounted(() => { observer = new ResizeObserver(() => chart?.resize()); if (target.value) observer.observe(target.value); void render() })
onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose() })
</script>
<template><section class="sm-card sm-chart-card"><SectionHeader :title="title" :description="description"><slot name="action" /></SectionHeader><div ref="target" class="sm-chart" :style="{ display: option && !loading ? 'block' : 'none' }" role="img" :aria-label="`${title}，${description ?? ''}`" /><EmptyState v-if="!option || loading" compact :title="emptyTitle" :description="emptyDescription" :loading="loading" /><slot /></section></template>
