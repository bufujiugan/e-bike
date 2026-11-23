<script setup lang="ts">
import { tr } from '../../i18n/language'
import AppIcon from '../AppIcon.vue'
import type { Metric } from '../../config/page-presentation'
import { computed } from 'vue'
const props = defineProps<Metric>()
const bars = computed(() => {
  const maximum = Math.max(1, ...(props.sparkline ?? []))
  return props.sparkline?.map(value => ({ value, height: value / maximum * 100 })) ?? []
})
</script>
<template><article class="sm-metric" :class="`sm-tone-${tone ?? 'blue'}`"><span class="sm-metric-icon"><AppIcon :name="icon ?? 'chart'" /></span><div class="sm-metric-content"><span class="sm-metric-label">{{ tr(label) }}</span><div class="sm-metric-value"><strong>{{ value === null ? '—' : typeof value === 'number' ? value.toLocaleString('zh-CN') : value }}</strong><small v-if="unit && value !== null">{{ tr(unit) }}</small></div><span v-if="hint" class="sm-metric-hint" :title="tr(hint)">{{ tr(hint) }}</span></div><span v-if="trend" class="sm-metric-trend">{{ trend }}</span><div v-if="bars.length" class="sm-metric-sparkline" role="img" :aria-label="tr('近 {value0} 日数据：{value1}', { value0: bars.length, value1: sparkline?.join('、') })"><i v-for="(bar, index) in bars" :key="index" :style="{ height: `${bar.height}%` }" :title="String(bar.value)" /></div></article></template>
