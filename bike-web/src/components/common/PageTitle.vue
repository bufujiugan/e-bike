<script setup lang="ts">
defineProps<{ title: string; description?: string }>()
</script>
<template><div class="sm-page-title"><h1>{{ title }}</h1><p v-if="description">{{ description }}</p></div></template>
