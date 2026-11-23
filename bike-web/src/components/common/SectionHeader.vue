<script setup lang="ts">
import { tr } from '../../i18n/language'
defineProps<{ title: string; description?: string }>()
</script>
<template><div class="sm-section-header"><div class="sm-section-heading"><h2>{{ tr(title) }}</h2><span v-if="description">{{ tr(description) }}</span></div><div v-if="$slots.default" class="sm-section-actions"><slot /></div></div></template>
