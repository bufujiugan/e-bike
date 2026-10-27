<script setup lang="ts">
import { computed } from 'vue'
const props = withDefaults(defineProps<{ value: number | null; label?: string; tone?: string; showLabel?: boolean }>(), { tone: 'blue', showLabel: true })
const percent = computed(() => props.value === null || !Number.isFinite(props.value) ? null : Math.max(0, Math.min(100, props.value)))
</script>
<template><div class="sm-progress" :class="`sm-tone-${tone}`"><div class="sm-progress-track" role="progressbar" :aria-label="label ?? '完成进度'" :aria-valuenow="percent ?? undefined" :aria-valuemin="0" :aria-valuemax="100"><span :style="{ width: `${percent ?? 0}%` }" /></div><span v-if="showLabel" class="sm-progress-label">{{ label ?? (percent === null ? '—' : `${Math.round(percent)}%`) }}</span></div></template>
