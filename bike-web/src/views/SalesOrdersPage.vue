<script setup lang="ts">
import { computed } from 'vue'
import { orderFlow, type ModuleData, type Metric } from '../config/page-presentation'
import { cell } from '../components/business/module-presentation'
import MetricGrid from '../components/dashboard/MetricGrid.vue'
import SectionHeader from '../components/common/SectionHeader.vue'
import ProcessFlow from '../components/business/ProcessFlow.vue'
const props = defineProps<{ module: ModuleData; loading: boolean; error: string }>()
function count(states: string[]) { return props.error ? null : props.module.rows.filter(row => states.includes(cell(props.module, row, '状态'))).length }
const metrics = computed<Metric[]>(() => [
  { label: '订单查询结果', value: props.error ? null : props.module.total ?? 0, icon: 'orders', hint: '订单明细条数 · 非去重订单数' },
  { label: '待确认', value: count(['草稿', '待确认', 'DRAFT', 'PENDING']), icon: 'clock', tone: 'orange', hint: '当前页明细' },
  { label: '履约中', value: count(['待出库', '部分出库', 'CONFIRMED', 'PARTIAL_SHIPPED']), icon: 'production', hint: '当前页明细' },
  { label: '已完成', value: count(['已完成', 'SHIPPED']), icon: 'check', tone: 'green', hint: '当前页明细' },
  { label: '异常订单', value: null, icon: 'warning', tone: 'red', hint: '暂无异常判定汇总' },
])
</script>
<template><MetricGrid :items="metrics" :loading="loading" /><section class="sm-card"><SectionHeader title="订单履约流程" description="业务流程说明 · 订单实际状态以列表与详情为准" /><ProcessFlow :steps="orderFlow" illustrated /></section><slot /></template>
