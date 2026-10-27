<script setup lang="ts">
import AppIcon from '../AppIcon.vue'
defineProps<{ modelValue: string; placeholder?: string; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; search: [] }>()
</script>
<template><el-input :model-value="modelValue" :placeholder="placeholder ?? '搜索…'" :aria-label="placeholder ?? '搜索'" :disabled="disabled" clearable class="sm-search" @update:model-value="emit('update:modelValue', $event)" @keyup.enter="emit('search')"><template #prefix><AppIcon name="search" /></template></el-input></template>
