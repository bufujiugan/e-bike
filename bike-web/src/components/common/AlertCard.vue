<script setup lang="ts">
import AppIcon from '../AppIcon.vue'
withDefaults(defineProps<{ title?: string; description?: string; tone?: 'info' | 'error' }>(), { tone: 'info' })
</script>
<template><div class="sm-alert" :class="`sm-alert--${tone}`" :role="tone === 'error' ? 'alert' : 'note'"><AppIcon :name="tone === 'error' ? 'warning' : 'info'" /><div><strong v-if="title">{{ title }}</strong><span v-if="description">{{ description }}</span><slot /></div><slot name="action" /></div></template>
