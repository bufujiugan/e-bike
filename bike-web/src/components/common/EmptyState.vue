<script setup lang="ts">
import AppIcon from '../AppIcon.vue'
withDefaults(defineProps<{ title?: string; description?: string; compact?: boolean; error?: boolean; loading?: boolean }>(), { title: '暂无数据', description: '当前筛选条件下暂无数据，可调整筛选条件或刷新重试。' })
</script>
<template><div class="sm-empty" :class="{ 'is-compact': compact, 'is-error': error }" :role="error ? 'alert' : 'status'"><div class="sm-empty-art" aria-hidden="true"><AppIcon :name="loading ? 'refresh' : error ? 'warning' : 'inventory'" /><i /></div><div><strong>{{ loading ? '正在加载数据' : title }}</strong><p>{{ loading ? '正在读取后端数据，请稍候。' : description }}</p><div v-if="$slots.default" class="sm-empty-actions"><slot /></div></div></div></template>
