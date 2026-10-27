<script setup lang="ts">
withDefaults(defineProps<{ total: number; page: number; pageSize: number; sizes?: number[]; disabled?: boolean; jumper?: boolean }>(), { sizes: () => [] })
const emit = defineEmits<{ 'update:page': [value: number]; 'update:pageSize': [value: number]; change: []; sizeChange: [] }>()
</script>
<template><footer class="sm-pagination"><span>共 <strong>{{ total }}</strong> 条记录</span><el-pagination :total="total" :current-page="page" :page-size="pageSize" :page-sizes="sizes" :disabled="disabled" :layout="sizes.length ? 'sizes, prev, pager, next, jumper' : jumper ? 'prev, pager, next, jumper' : 'prev, pager, next'" background @update:current-page="emit('update:page', $event)" @update:page-size="emit('update:pageSize', $event)" @current-change="emit('change')" @size-change="emit('sizeChange')" /><small v-if="!sizes.length">{{ pageSize }} 条 / 页</small></footer></template>
