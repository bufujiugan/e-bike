<script setup lang="ts">
import { tr } from '../i18n/language'

defineProps<{ lines: {
  skuId: number; skuCode: string; modelName: string; specification?: string
  orderedQty: number; shippedQty: number; closedQty: number
}[]; sources?: { lockId: number; lockNo: string; skuId: number; quantity: number; expiresAt: string; renewedExpiresAt?: string | null }[] }>()
</script>

<template>
  <section :aria-label='tr("SKU 明细")'>
    <h3>{{ tr("SKU 明细") }}</h3>
    <el-table :data="lines" row-key="skuId">
      <el-table-column prop="skuCode" label="SKU" min-width="140" />
      <el-table-column prop="modelName" :label='tr("车型")' min-width="115" />
      <el-table-column prop="specification" :label='tr("规格")' min-width="100" />
      <el-table-column prop="orderedQty" :label='tr("订购数量")' width="95" />
      <el-table-column prop="shippedQty" :label='tr("已出库")' width="85" />
      <el-table-column prop="closedQty" :label='tr("已关闭")' width="85" />
      <el-table-column :label='tr("待出库")' width="85">
        <template #default="{ row }">{{ row.orderedQty - row.shippedQty - row.closedQty }}</template>
      </el-table-column>
    </el-table>
    <template v-if="sources?.length">
      <h4>{{ tr("锁定来源") }}</h4>
      <el-table :data="sources" row-key="lockId">
        <el-table-column label="SKU" min-width="140">
          <template #default="{ row }">{{ lines.find(line => line.skuId === row.skuId)?.skuCode }}</template>
        </el-table-column>
        <el-table-column prop="lockNo" :label='tr("锁定单号")' min-width="170" />
        <el-table-column prop="quantity" :label='tr("本次转出")' width="100" />
        <el-table-column :label='tr("原锁定截止")' min-width="155">
          <template #default="{ row }">{{ row.expiresAt?.replace('T', ' ').slice(0, 19) }}</template>
        </el-table-column>
        <el-table-column v-if="sources.some(source => source.renewedExpiresAt)" :label='tr("续占用截止")' min-width="155">
          <template #default="{ row }">{{ row.renewedExpiresAt?.replace('T', ' ').slice(0, 19) ?? '—' }}</template>
        </el-table-column>
      </el-table>
    </template>
  </section>
</template>
