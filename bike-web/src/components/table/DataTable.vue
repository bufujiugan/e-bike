<script setup lang="ts">
import EmptyState from '../common/EmptyState.vue'
withDefaults(defineProps<{ rows: any[]; loading?: boolean; error?: string; emptyTitle?: string; emptyDescription?: string; rowKey?: string; maxHeight?: string | number }>(), { emptyTitle: '暂无记录', emptyDescription: '当前筛选条件下暂无数据，可调整条件或刷新重试。' })
defineOptions({ inheritAttrs: false })
</script>
<template><div class="sm-data-table" :aria-busy="loading"><el-table v-bind="$attrs" :data="rows" :row-key="rowKey" :max-height="maxHeight" v-loading="loading" scrollbar-always-on><slot /><template #empty><EmptyState :title="error ? '数据加载失败' : emptyTitle" :description="error || emptyDescription" :error="!!error" :loading="loading"><slot name="empty-action" /></EmptyState></template></el-table></div></template>
