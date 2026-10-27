<script setup lang="ts">
import AppIcon from '../AppIcon.vue'
import type { ProcessStep } from '../../config/page-presentation'
defineProps<{ step: ProcessStep; index: number; illustrated?: boolean }>()
</script>
<template><li class="sm-process-node" :class="[step.state ?? 'reference', { illustrated }]"><span class="sm-process-circle"><template v-if="illustrated && step.icon"><sup>{{ index + 1 }}</sup><AppIcon :name="step.icon" /></template><template v-else>{{ index + 1 }}</template></span><strong>{{ step.label }}</strong><small v-if="step.description">{{ step.description }}</small><div v-if="step.count != null || step.percent != null" class="sm-process-count"><b v-if="step.count != null">{{ step.count }}</b><span v-if="step.percent != null">{{ step.percent }}%</span></div></li></template>
