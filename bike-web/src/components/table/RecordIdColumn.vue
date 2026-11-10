<script setup lang="ts">
withDefaults(defineProps<{ prop?: string; label?: string }>(), { prop: 'id', label: 'ID' })
</script>

<template>
  <el-table-column :prop="prop" :label="label" min-width="170">
    <template #default="{ row }"><span class="record-id">{{ row[prop] ?? '—' }}</span></template>
  </el-table-column>
</template>

<style scoped>
.record-id { font-variant-numeric: tabular-nums; overflow-wrap: anywhere; white-space: normal; user-select: text; }
</style>
