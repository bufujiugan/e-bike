<script setup lang="ts">
import { tr } from '../../i18n/language'
defineProps<{ title: string; description?: string }>()
</script>
<template><div class="sm-page-title"><h1>{{ tr(title) }}</h1><p v-if="description">{{ tr(description) }}</p></div></template>
