<script setup lang="ts">
import AppIcon from '../AppIcon.vue'
import type { Metric } from '../../config/page-presentation'
import { computed } from 'vue'
const props = defineProps<Metric>()
const bars = computed(() => {
  const maximum = Math.max(1, ...(props.sparkline ?? []))
  return props.sparkline?.map(value => ({ value, height: value / maximum * 100 })) ?? []
})
</script>
<template><article class="sm-metric" :class="`sm-tone-${tone ?? 'blue'}`"><span class="sm-metric-icon"><AppIcon :name="icon ?? 'chart'" /></span><div class="sm-metric-content"><span class="sm-metric-label">{{ label }}</span><div class="sm-metric-value"><strong>{{ value === null ? '—' : typeof value === 'number' ? value.toLocaleString('zh-CN') : value }}</strong><small v-if="unit && value !== null">{{ unit }}</small></div><span v-if="hint" class="sm-metric-hint" :title="hint">{{ hint }}</span></div><span v-if="trend" class="sm-metric-trend">{{ trend }}</span><div v-if="bars.length" class="sm-metric-sparkline" role="img" :aria-label="`近 ${bars.length} 日数据：${sparkline?.join('、')}`"><i v-for="(bar, index) in bars" :key="index" :style="{ height: `${bar.height}%` }" :title="String(bar.value)" /></div></article></template>
