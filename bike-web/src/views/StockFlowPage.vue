<script setup lang="ts">
import StockMovementOverview from './StockMovementOverview.vue'
import type { TrendPoint } from '../components/charts/TrendCard.vue'
defineProps<{ points: TrendPoint[] | null; loading: boolean; error: string; permitted: boolean }>()
</script>
<template><StockMovementOverview direction="both" v-bind="$props"><slot /></StockMovementOverview></template>
