<script setup lang="ts">
import type { ModuleData } from '../../config/page-presentation'
import SectionHeader from '../common/SectionHeader.vue'
import FilterBar from '../common/FilterBar.vue'
import SearchBar from '../common/SearchBar.vue'
import ActionToolbar from '../common/ActionToolbar.vue'
import DataTable from '../table/DataTable.vue'
import Pagination from '../table/Pagination.vue'
import StatusTag from './StatusTag.vue'
import ProgressBar from './ProgressBar.vue'
import { bindingProgress } from './module-presentation'
import AppIcon from '../AppIcon.vue'
defineProps<{ module: ModuleData; title: string; search: string; page: number; pageSize: number; loading: boolean; error: string; columnWidth: (column: string) => number }>()
const emit = defineEmits<{ 'update:search': [value: string]; 'update:page': [value: number]; 'update:pageSize': [value: number]; refresh: []; reset: []; pageChange: []; sizeChange: [] }>()
</script>
<template>
  <section class="sm-card sm-module-list"><SectionHeader :title="title"><span class="sm-scope-label">{{ module.total ?? 0 }} 条查询结果</span></SectionHeader>
    <FilterBar><SearchBar :model-value="search" :placeholder="`搜索${title.replace('列表', '')}…`" @update:model-value="emit('update:search', $event)" /><template #actions><ActionToolbar><el-button type="primary" :loading="loading" @click="emit('refresh')"><AppIcon name="refresh" />刷新</el-button><el-button :disabled="loading" @click="emit('reset')">重置</el-button><slot name="actions" /></ActionToolbar></template></FilterBar>
    <DataTable :rows="module.rows" :loading="loading" :error="error" :empty-title="search ? '没有符合条件的记录' : `暂无${title.replace('列表', '')}`" :max-height="540">
      <el-table-column v-for="(column, index) in module.columns" :key="column" :label="column" :prop="String(index)" :min-width="columnWidth(column)" show-overflow-tooltip><template #default="{ row }"><StatusTag v-if="/状态/.test(column) && row[index] && row[index] !== '-'" :label="row[index]" /><ProgressBar v-else-if="column === '完成进度'" :value="bindingProgress(row[index])" :label="row[index]" /><span v-else :class="{ 'sm-code-cell': /聚合码|订单号|业务单号/.test(column) }">{{ row[index] || '—' }}</span></template></el-table-column>
      <el-table-column label="操作" fixed="right" :width="200"><template #default="{ row }"><slot name="row-actions" :row="row" /></template></el-table-column>
      <template #empty-action><el-button size="small" :disabled="loading" @click="emit('refresh')">刷新数据</el-button><el-button v-if="search" size="small" @click="emit('reset')">清除搜索</el-button></template>
    </DataTable>
    <Pagination :total="module.total ?? module.rows.length" :page="page" :page-size="pageSize" :sizes="[10, 20, 50, 100]" :disabled="loading" @update:page="emit('update:page', $event)" @update:page-size="emit('update:pageSize', $event)" @change="emit('pageChange')" @size-change="emit('sizeChange')" />
    <p class="sm-table-source">数据来源：后端接口 · 保留全部 {{ module.columns.length }} 个业务字段</p>
  </section>
</template>
