<script setup lang="ts">
import { computed } from 'vue'
import type { ModuleData, Metric } from '../config/page-presentation'
import { assemblyFlow } from '../config/page-presentation'
import { cell, bindingProgress } from '../components/business/module-presentation'
import MetricGrid from '../components/dashboard/MetricGrid.vue'
import SectionHeader from '../components/common/SectionHeader.vue'
import EmptyState from '../components/common/EmptyState.vue'
import ProcessFlow from '../components/business/ProcessFlow.vue'
import ProgressBar from '../components/business/ProgressBar.vue'
import StatusTag from '../components/business/StatusTag.vue'
import AppIcon from '../components/AppIcon.vue'
const props = defineProps<{ module: ModuleData; loading: boolean; error: string; todayAssembled: number | null }>()
const current = computed(() => props.module.rows.find(row => cell(props.module, row, '状态') === '作业中') ?? props.module.rows[0])
const date = computed(() => props.module.stats.find(stat => stat.label === '查询日期')?.value ?? '—')
const metrics = computed<Metric[]>(() => [
  { label: '查询结果', value: props.error ? null : props.module.total ?? 0, icon: 'tasks', hint: '当前筛选全部结果' },
  { label: '今日完成台数', value: props.todayAssembled, tone: 'green', icon: 'check', hint: props.todayAssembled === null ? '暂无汇总数据' : '运营统计 · 今日组装' },
  { label: '当前在组装', value: props.error ? null : props.module.rows.filter(row => cell(props.module, row, '状态') === '作业中').length, icon: 'production', hint: '当前页任务' },
  { label: '查询日期', value: date.value, icon: 'calendar', hint: '后端查询日期' },
])
</script>
<template><MetricGrid :items="metrics" :loading="loading" /><div class="sm-two-columns"><section class="sm-card"><SectionHeader title="产线组装进度" description="产线计划与完成情况" /><EmptyState compact title="暂无产线进度数据" description="当前记录未提供产线归属与计划产量，任务扫码进度见下方列表。" /></section><div class="sm-stack"><section class="sm-card"><SectionHeader title="组装工艺流程" description="工艺说明 · 不表示实时工序完成状态" /><ProcessFlow :steps="assemblyFlow" /></section><section class="sm-card"><SectionHeader title="当前工位记录" description="当前页作业任务的最近扫码记录" /><div v-if="current && !error" class="sm-station"><span class="sm-station-icon"><AppIcon name="vehicles" /></span><div><strong>任务 {{ cell(module, current, '任务号') }}</strong><p>车架 SN：{{ cell(module, current, '车架 SN') }}</p><p>最近工位：{{ cell(module, current, '当前工位') }} · {{ cell(module, current, '操作员') }}</p><StatusTag :label="cell(module, current, '状态')" /></div><div class="sm-station-progress"><span class="sm-caption">四码扫码进度</span><strong>{{ bindingProgress(cell(module, current, '完成进度')) ?? '—' }}%</strong><ProgressBar :value="bindingProgress(cell(module, current, '完成进度'))" :show-label="false" /></div></div><EmptyState v-else compact :loading="loading" title="暂无工位作业记录" description="当前查询结果中没有可展示的扫码任务。" /></section></div></div><slot /></template>
