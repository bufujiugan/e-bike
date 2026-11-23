<script setup lang="ts">
import { tr } from '../i18n/language'
import { computed, ref, watch } from 'vue'
import api from '../api'
import { salesTypeLabel } from '../sales-routing'
import StatusTag from './business/StatusTag.vue'
import EmptyState from './common/EmptyState.vue'
import DataTable from './table/DataTable.vue'

type Shipment = {
  vehicleQrCode: string
  frameSn: string
  stockOutNo: string
  stockOutTime: string
}

type OrderOperation = {
  businessType: string
  action: string
  operatorName: string | null
  operationTime: string
  stockOutNo: string | null
}

type OrderDetails = {
  orderNo: string
  customerName: string
  agentName: string | null
  modelName: string
  orderQty: number
  shippedQty: number
  status: string
  salesType: string | null
  hasLinkedPi: boolean
  purchaseContractNo: string | null
  sourceLockNo: string | null
  plannedShipDate: string | null
  createdAt: string
  updatedAt: string
  remark: string | null
  shippedVehicles: Shipment[]
  operations?: OrderOperation[]
}

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ orderNo: string }>()
const order = ref<OrderDetails>()
const loading = ref(false)
const error = ref('')
const refreshVersion = ref(0)

const statusNames: Record<string, string> = {
  DRAFT: '草稿', PENDING: '待确认', CONFIRMED: '待出库', PARTIAL_SHIPPED: '部分出库',
  SHIPPED: '已完成', CANCELLED: '已作废', CLOSED: '已关闭',
}
const orderOperations = computed(() => order.value?.operations?.filter(operation => !operation.stockOutNo) ?? [])
const createOperation = computed(() => orderOperations.value.find(operation => operation.businessType === 'ORDER_CREATE'))
const confirmOperation = computed(() => orderOperations.value.find(operation =>
  operation.action === '确认订单' || operation.action === '随PI确认履约订单',
))
const shipmentOperators = computed(() => {
  const names = new Map<string, string>()
  for (const operation of order.value?.operations ?? []) {
    if (operation.stockOutNo && operation.operatorName) names.set(operation.stockOutNo, operation.operatorName)
  }
  return names
})

function formatTime(value?: string | null) {
  return value?.replace('T', ' ').slice(0, 19) || '—'
}

watch([visible, () => props.orderNo, refreshVersion], async ([isVisible, orderNo], _, onCleanup) => {
  if (!isVisible || !orderNo) return
  const request = new AbortController()
  onCleanup(() => request.abort())
  order.value = undefined
  loading.value = true
  error.value = ''

  try {
    const response = await api.get<{ data: OrderDetails }>(`/api/orders/by-no/${encodeURIComponent(orderNo)}`, {
      signal: request.signal,
    })
    if (!request.signal.aborted) order.value = response.data.data
  } catch (cause) {
    if (request.signal.aborted) return
    const response = api.isAxiosError<{ code?: string }>(cause) ? cause.response : undefined
    if (response?.status === 403) error.value = '当前账号无权查看此订单。'
    else if (response?.status === 404 || response?.data?.code === 'ORDER_NOT_FOUND') {
      error.value = '订单已不存在，可关闭详情并重新查询。'
    }
    else error.value = '订单详情加载失败，请重试。'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
})
</script>

<template>
  <el-drawer
    v-model="visible"
    :title='tr("订单详情")'
    size="min(820px, 100vw)"
    append-to-body
  >
    <EmptyState v-if="loading" loading />
    <EmptyState v-else-if="error" error :title='tr("无法打开订单详情")' :description="tr(error)">
      <el-button size="small" @click="refreshVersion++">{{ tr("重试") }}</el-button>
    </EmptyState>
    <div v-else-if="order" class="completed-order-detail">
      <div class="detail-summary">
        <h2>{{ order.orderNo }}</h2>
        <StatusTag :label="tr(statusNames[order.status] ?? order.status)" />
      </div>

      <section :aria-label='tr("订单基本信息")'>
        <h3>{{ tr("基本信息") }}</h3>
        <dl class="detail-fields">
          <div><dt>{{ tr("客户") }}</dt><dd>{{ order.customerName || '—' }}</dd></div>
          <div><dt>{{ tr("代理商") }}</dt><dd>{{ order.agentName || '—' }}</dd></div>
          <div><dt>{{ tr("车型") }}</dt><dd>{{ order.modelName || '—' }}</dd></div>
          <div><dt>{{ tr("销售类型") }}</dt><dd>{{ order.salesType ? salesTypeLabel(order.salesType) : '—' }}</dd></div>
          <div><dt>{{ tr("订单数量") }}</dt><dd>{{ order.orderQty }} {{ tr("台") }}</dd></div>
          <div><dt>{{ tr("已出库") }}</dt><dd>{{ order.shippedQty }} {{ tr("台") }}</dd></div>
          <div><dt>{{ tr("计划出库日") }}</dt><dd>{{ order.plannedShipDate || '—' }}</dd></div>
          <div><dt>{{ tr("最新出库时间") }}</dt><dd>{{ formatTime(order.shippedVehicles[0]?.stockOutTime) }}</dd></div>
          <div v-if="order.operations"><dt>{{ tr("创建人") }}</dt><dd>{{ createOperation?.operatorName || tr('未记录') }}</dd></div>
          <div><dt>{{ tr("创建时间") }}</dt><dd>{{ formatTime(order.createdAt) }}</dd></div>
          <div v-if="order.operations"><dt>{{ tr("确认人") }}</dt><dd>{{ confirmOperation?.operatorName || tr('未记录') }}</dd></div>
          <div v-if="order.operations"><dt>{{ tr("确认时间") }}</dt><dd>{{ formatTime(confirmOperation?.operationTime) }}</dd></div>
          <div><dt>{{ tr("最近更新时间") }}</dt><dd>{{ formatTime(order.updatedAt) }}</dd></div>
          <div><dt>{{ tr("来源锁定单号") }}</dt><dd>{{ order.sourceLockNo || '—' }}</dd></div>
          <div v-if="order.purchaseContractNo"><dt>{{ tr("购销合同号") }}</dt><dd>{{ order.purchaseContractNo }}</dd></div>
          <div><dt>{{ tr("关联 PI") }}</dt><dd>{{ order.hasLinkedPi ? tr('已关联') : tr('未关联') }}</dd></div>
          <div class="detail-remark"><dt>{{ tr("备注") }}</dt><dd>{{ order.remark || '—' }}</dd></div>
        </dl>
      </section>

      <section v-if="order.operations" :aria-label='tr("订单操作记录")'>
        <h3>{{ tr("操作记录") }}</h3>
        <DataTable :rows="orderOperations" max-height="280" :empty-title='tr("暂无订单操作记录")' empty-description="">
          <el-table-column prop="action" :label='tr("操作")' min-width="145" />
          <el-table-column :label='tr("操作人")' min-width="105">
            <template #default="{ row }">{{ row.operatorName || tr('未记录') }}</template>
          </el-table-column>
          <el-table-column :label='tr("操作时间")' min-width="170">
            <template #default="{ row }">{{ formatTime(row.operationTime) }}</template>
          </el-table-column>
        </DataTable>
      </section>

      <section :aria-label='tr("车辆出库明细")'>
        <h3>{{ tr("出库明细（") }}{{ order.shippedVehicles.length }} {{ tr("台）") }}</h3>
        <DataTable :rows="order.shippedVehicles" row-key="stockOutNo" max-height="360" :empty-title='tr("暂无有效出库记录")' empty-description="">
          <el-table-column prop="stockOutNo" :label='tr("出库单号")' min-width="155" show-overflow-tooltip />
          <el-table-column v-if="order.operations" :label='tr("出库操作人")' min-width="100">
            <template #default="{ row }">{{ shipmentOperators.get(row.stockOutNo) || tr('未记录') }}</template>
          </el-table-column>
          <el-table-column :label='tr("出库时间")' min-width="165">
            <template #default="{ row }">{{ formatTime(row.stockOutTime) }}</template>
          </el-table-column>
          <el-table-column prop="vehicleQrCode" :label='tr("整车总码")' min-width="160" show-overflow-tooltip />
          <el-table-column prop="frameSn" :label='tr("车架码")' min-width="140" show-overflow-tooltip />
        </DataTable>
      </section>
    </div>
  </el-drawer>
</template>

<style scoped>
.completed-order-detail {
  display: grid;
  gap: 24px;
  min-width: 0;
}

.completed-order-detail section {
  min-width: 0;
}

.detail-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 16px;
}

.detail-summary h2,
.completed-order-detail h3 {
  margin: 0;
  color: var(--el-text-color-primary);
}

.detail-summary h2 {
  font-size: 19px;
  overflow-wrap: anywhere;
}

.completed-order-detail h3 {
  margin-bottom: 12px;
  font-size: 15px;
}

.detail-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin: 0;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.detail-fields > div {
  display: grid;
  gap: 4px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.detail-fields dt {
  color: var(--el-text-color-regular);
  font-size: 12px;
}

.detail-fields dd {
  margin: 0;
  color: var(--el-text-color-primary);
  overflow-wrap: anywhere;
}

.detail-fields .detail-remark {
  grid-column: 1 / -1;
  border-bottom: 0;
}

.detail-remark dd {
  white-space: pre-wrap;
}
</style>
