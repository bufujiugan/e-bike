<script setup lang="ts">
import { tr } from '../../i18n/language'
import { computed } from 'vue'
import PurchaseContractNumber from '../PurchaseContractNumber.vue'
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
import PartPhoto from '../PartPhoto.vue'
const props = defineProps<{ module: ModuleData; title: string; search: string; page: number; pageSize: number; loading: boolean; error: string; columnWidth: (column: string) => number }>()
// 仅调整展示顺序，原始行数组仍完整传给编辑和状态操作。
const displayColumns = computed(() => {
  const columns = props.module.columns.map((column, index) => ({ column, index }))
    .sort((a, b) => Number(/(?:^| )ID$/.test(b.column)) - Number(/(?:^| )ID$/.test(a.column)))
  const photo = columns.findIndex(({ column }) => column === '图片')
  if (photo >= 0) columns.splice(1, 0, ...columns.splice(photo, 1))
  return columns
})
const emit = defineEmits<{ 'update:search': [value: string]; 'update:page': [value: number]; 'update:pageSize': [value: number]; refresh: []; reset: []; pageChange: []; sizeChange: [] }>()
</script>
<template>
  <section class="sm-card sm-module-list"><SectionHeader :title="tr(title)"><span class="sm-scope-label">{{ tr('{count} 条查询结果', { count: module.total ?? 0 }) }}</span></SectionHeader>
    <FilterBar><SearchBar :model-value="search" :placeholder="tr('搜索{title}…', { title: tr(title.replace(/列表$/, '')) })" @update:model-value="emit('update:search', $event)" /><template #actions><ActionToolbar><el-button type="primary" :loading="loading" @click="emit('refresh')"><AppIcon name="refresh" />{{ tr("刷新") }}</el-button><el-button :disabled="loading" @click="emit('reset')">{{ tr("重置") }}</el-button><slot name="actions" /></ActionToolbar></template></FilterBar>
    <DataTable :rows="module.rows" :loading="loading" :error="error" :empty-title="search ? tr('没有符合条件的记录') : tr('暂无{title}', { title: tr(title.replace(/列表$/, '')) })" :max-height="540">
      <el-table-column v-for="{ column, index } in displayColumns" :key="column" :label="tr(column)" :prop="String(index)" :min-width="column === '图片' ? 96 : column === '购销合同号' ? 260 : /(?:^| )ID$/.test(column) ? 170 : columnWidth(column)" :show-overflow-tooltip="column !== '图片'"><template #default="{ row }"><PartPhoto v-if="column === '图片' && /^[0-9]+$/.test(row[index])" :key="row[index]" :part-id="row[index]" :part-sn="row[0]" /><StatusTag v-else-if="/状态/.test(column) && row[index] && row[index] !== '-'" :label="row[index]" /><ProgressBar v-else-if="column === '完成进度'" :value="bindingProgress(row[index])" :label="row[index]" /><PurchaseContractNumber v-else-if="column === '购销合同号'" :value="row[index] === '-' ? null : row[index]" /><span v-else :class="{ 'sm-code-cell': /聚合码|订单号|业务单号/.test(column) }">{{ row[index] === '-' && column === '图片' ? '—' : row[index] || '—' }}</span></template></el-table-column>
      <el-table-column :label='tr("操作")' fixed="right" :width="200"><template #default="{ row }"><slot name="row-actions" :row="row" /></template></el-table-column>
      <template #empty-action><el-button size="small" :disabled="loading" @click="emit('refresh')">{{ tr("刷新数据") }}</el-button><el-button v-if="search" size="small" @click="emit('reset')">{{ tr("清除搜索") }}</el-button></template>
    </DataTable>
    <Pagination :total="module.total ?? module.rows.length" :page="page" :page-size="pageSize" :sizes="[10, 20, 50, 100]" :disabled="loading" @update:page="emit('update:page', $event)" @update:page-size="emit('update:pageSize', $event)" @change="emit('pageChange')" @size-change="emit('sizeChange')" />
    <p class="sm-table-source">{{ tr('数据来源：后端接口 · 保留全部 {count} 个业务字段', { count: module.columns.length }) }}</p>
  </section>
</template>
