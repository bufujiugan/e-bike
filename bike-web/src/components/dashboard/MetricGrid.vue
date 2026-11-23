<script setup lang="ts">
import { tr } from '../../i18n/language'
import MetricCard from './MetricCard.vue'
import type { Metric } from '../../config/page-presentation'
defineProps<{ items: Metric[]; loading?: boolean }>()
</script>
<template><section class="sm-metric-grid" :style="{ '--metric-count': items.length }" :aria-busy="loading" :aria-label='tr("业务指标")'><MetricCard v-for="metric in items" :key="metric.label" v-bind="metric" :value="loading ? null : metric.value" :label="tr(metric.label)" :unit="metric.unit && tr(metric.unit)" :hint="loading ? tr('正在加载') : metric.hint && tr(metric.hint)" /></section></template>
