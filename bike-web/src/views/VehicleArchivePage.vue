<script setup lang="ts">
import { computed } from 'vue'
import { traceFlow, type ModuleData, type Metric } from '../config/page-presentation'
import MetricGrid from '../components/dashboard/MetricGrid.vue'
import SectionHeader from '../components/common/SectionHeader.vue'
import ProcessFlow from '../components/business/ProcessFlow.vue'
const props = defineProps<{ module: ModuleData; loading: boolean; error: string }>()
const metrics = computed<Metric[]>(() => [
  { label: '查询结果', value: props.error ? null : props.module.total ?? 0, icon: 'tasks', hint: '当前筛选全部结果' },
  { label: '今日查询次数', value: null, icon: 'chart', hint: '暂无查询次数统计' },
  { label: '追溯命中率', value: null, icon: 'trace', tone: 'green', hint: '暂无命中率统计' },
  { label: '查询日期', value: props.module.stats.find(stat => stat.label === '查询日期')?.value ?? '—', icon: 'calendar', hint: '后端查询日期' },
])
</script>
<template><MetricGrid :items="metrics" :loading="loading" /><section class="sm-card sm-trace-chain"><SectionHeader title="整车追溯链路" description="以聚合码为主线，关联整车与核心部件，记录完整生命周期" /><ProcessFlow :steps="traceFlow" illustrated /></section><slot /></template>
