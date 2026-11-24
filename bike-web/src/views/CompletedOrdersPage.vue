<script setup lang="ts">
import { tr } from '../i18n/language'
import { ref, watch } from 'vue'
import api from '../api'
import CompletedOrderDetails from '../components/CompletedOrderDetails.vue'
import StatusTag from '../components/business/StatusTag.vue'
import DataTable from '../components/table/DataTable.vue'
import Pagination from '../components/table/Pagination.vue'
import { salesTypeLabel } from '../sales-routing'

type CompletedOrder = {
  orderNo: string
  customerName: string
  modelName: string
  orderQty: number
  shippedQty: number
  salesType: string | null
  createdAt: string
}

const pageSize = 20
const page = ref(1)
const searchInput = ref('')
const keyword = ref('')
const refreshVersion = ref(0)
const orders = ref<CompletedOrder[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const detailOrderNo = ref('')
const detailVisible = ref(false)

function openDetails(orderNo: string) {
  detailOrderNo.value = orderNo
  detailVisible.value = true
}

function search() {
  keyword.value = searchInput.value.trim()
  page.value = 1
  refreshVersion.value++
}

watch([page, keyword, refreshVersion], async (_, __, onCleanup) => {
  const request = new AbortController()
  onCleanup(() => request.abort())
  loading.value = true
  error.value = ''

  try {
    const response = await api.get<{ data: { records: CompletedOrder[]; total: number } }>('/api/orders', {
      params: { status: 'SHIPPED', keyword: keyword.value || undefined, page: page.value, pageSize },
      signal: request.signal,
    })
    if (request.signal.aborted) return
    const result = response.data.data
    total.value = result.total
    const lastPage = Math.max(1, Math.ceil(result.total / pageSize))
    if (page.value > lastPage) {
      page.value = lastPage
      return
    }
    orders.value = result.records
  } catch {
    if (request.signal.aborted) return
    orders.value = []
    error.value = '已完成订单加载失败，请重试。'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}, { immediate: true })
</script>

<template>
  <section class="sm-card sm-module-list" :aria-label='tr("已完成订单列表")'>
    <form class="completed-orders-filter" role="search" :aria-label='tr("查询已完成订单")' @submit.prevent="search">
      <el-input v-model="searchInput" :aria-label='tr("订单号或客户名称")' :placeholder='tr("订单号或客户名称")' clearable />
      <el-button type="primary" native-type="submit" :loading="loading">{{ tr("查询") }}</el-button>
    </form>

    <DataTable
      :rows="orders"
      :loading="loading"
      :error="error"
      :empty-title="keyword ? tr('未找到符合条件的订单') : tr('暂无已完成订单')"
      :empty-description="keyword ? tr('可换一个订单号或客户名称查询。') : ''"
    >
      <el-table-column prop="orderNo" :label='tr("订单号")' min-width="180">
        <template #default="{ row }">
          <el-button link type="primary" :aria-label="tr('查看订单 {value0}', { value0: row.orderNo })" @click="openDetails(row.orderNo)">
            {{ row.orderNo }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column prop="customerName" :label='tr("客户")' min-width="160" show-overflow-tooltip />
      <el-table-column prop="modelName" :label='tr("车型")' min-width="140" show-overflow-tooltip />
      <el-table-column :label='tr("销售类型")' min-width="165">
        <template #default="{ row }">{{ row.salesType ? tr(salesTypeLabel(row.salesType)) : '—' }}</template>
      </el-table-column>
      <el-table-column :label='tr("创建时间")' min-width="155">
        <template #default="{ row }">{{ row.createdAt?.replace('T', ' ').slice(0, 19) || '—' }}</template>
      </el-table-column>
      <el-table-column prop="orderQty" :label='tr("订单数量")' width="100" align="right" />
      <el-table-column prop="shippedQty" :label='tr("已出库")' width="100" align="right" />
      <el-table-column :label='tr("状态")' width="100">
        <template #default><StatusTag :label='tr("已完成")' /></template>
      </el-table-column>
      <el-table-column :label='tr("操作")' width="100">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetails(row.orderNo)">{{ tr("查看详情") }}</el-button>
        </template>
      </el-table-column>
      <template v-if="error" #empty-action>
        <el-button size="small" @click="refreshVersion++">{{ tr("重试") }}</el-button>
      </template>
    </DataTable>

    <Pagination v-if="!error" v-model:page="page" :total="total" :page-size="pageSize" :disabled="loading" />
  </section>
  <CompletedOrderDetails v-model="detailVisible" :order-no="detailOrderNo" />
</template>

<style scoped>
.completed-orders-filter {
  display: flex;
  gap: 8px;
  max-width: 360px;
  margin-bottom: 16px;
}

.sm-module-list :deep(.el-button.is-link:focus-visible) {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 3px;
}
</style>
