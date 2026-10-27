<script setup lang="ts">
defineProps<{ title: string; description?: string }>()
</script>
<template><div class="sm-section-header"><div class="sm-section-heading"><h2>{{ title }}</h2><span v-if="description">{{ description }}</span></div><div v-if="$slots.default" class="sm-section-actions"><slot /></div></div></template>
