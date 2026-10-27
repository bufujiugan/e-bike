<script setup lang="ts">
import MetricCard from './MetricCard.vue'
import type { Metric } from '../../config/page-presentation'
defineProps<Metric>()
</script>
<template><MetricCard v-bind="$props" /></template>
