<script setup lang="ts">
import MetricCard from './MetricCard.vue'
import type { Metric } from '../../config/page-presentation'
defineProps<{ items: Metric[]; loading?: boolean }>()
</script>
<template><section class="sm-metric-grid" :style="{ '--metric-count': items.length }" :aria-busy="loading" aria-label="业务指标"><MetricCard v-for="metric in items" :key="metric.label" v-bind="metric" :value="loading ? null : metric.value" :hint="loading ? '正在加载' : metric.hint" /></section></template>
