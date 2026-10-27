<script setup lang="ts">
import { computed } from 'vue'
import type { Metric } from '../../config/page-presentation'
import MetricGrid from '../dashboard/MetricGrid.vue'
import ChartCard from '../charts/ChartCard.vue'
import { barOption, donutOption } from '../charts/chart-options'
type SkuRow = { physicalQty: number; quarantinedQty: number; temporaryLockedQty: number; orderReservedQty: number; availableQty: number; shortageQty: number; maxNewLockQty: number }
const props = defineProps<{ rows: SkuRow[]; loading: boolean; error: string }>()
function sum(key: keyof SkuRow) { return props.rows.reduce((total, row) => total + row[key], 0) }
const metrics = computed<Metric[]>(() => [
  { label: '当前锁定', value: props.error ? null : sum('temporaryLockedQty') + sum('orderReservedQty'), icon: 'lock', hint: '当前页 · 临时锁定 + 订单占用' },
  { label: '订单占用', value: props.error ? null : sum('orderReservedQty'), icon: 'orders', hint: '当前页 SKU' },
  { label: '临时锁定', value: props.error ? null : sum('temporaryLockedQty'), icon: 'clock', tone: 'orange', hint: '当前页 SKU' },
  { label: '可新增锁定', value: props.error || props.rows.length !== 1 ? null : props.rows[0]!.maxNewLockQty, icon: 'inventory', tone: 'green', hint: props.rows.length === 1 ? '当前唯一 SKU 上限' : '逐 SKU 查看 · 上限不可相加' },
])
const sources = computed(() => props.error ? null : donutOption([{ name: '订单占用', value: sum('orderReservedQty') }, { name: '临时锁定', value: sum('temporaryLockedQty') }], '当前页锁定量'))
const structure = computed(() => props.error ? null : barOption([{ name: '订单占用', value: sum('orderReservedQty') }, { name: '临时锁定', value: sum('temporaryLockedQty') }, { name: 'SKU净可用', value: sum('availableQty') }]))
const health = computed(() => props.error ? null : barOption([{ name: '物理库存', value: sum('physicalQty') }, { name: '隔离库存', value: sum('quarantinedQty') }, { name: '库存缺口', value: sum('shortageQty') }]))
</script>
<template><MetricGrid :items="metrics" :loading="loading" /><div class="sm-chart-grid"><ChartCard title="锁定来源分析" description="当前页 SKU · 已提供的锁定来源" :option="sources" :loading="loading" /><ChartCard title="锁定结构" description="当前页 SKU · 保留原库存口径" :option="structure" :loading="loading" /><ChartCard title="库存健康度" description="当前页数量 · 不虚构健康阈值" :option="health" :loading="loading" /></div></template>
