<script setup lang="ts">
import { tr } from '../../i18n/language'
import ProcessNode from './ProcessNode.vue'
import type { ProcessStep } from '../../config/page-presentation'
defineProps<{ steps: ProcessStep[]; illustrated?: boolean; label?: string }>()
</script>
<template><ol class="sm-process-flow" :class="{ 'is-illustrated': illustrated }" :aria-label="tr(label ?? tr('业务流程'))" :style="{ '--step-count': steps.length }"><ProcessNode v-for="(step, index) in steps" :key="step.label" :step="{ ...step, label: tr(step.label), description: step.description && tr(step.description) }" :index="index" :illustrated="illustrated" /></ol></template>
