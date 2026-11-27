<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { tr } from '../i18n/language'
import { createUuid } from '../utils/uuid'
import {
  customerStatusError, type CustomerInventory, type CustomerOrder, type CustomerOrderDetail,
  type CustomerStatusView, type Page, type PartPrice,
} from '../customer-status-api'

const props = defineProps<{ view: CustomerStatusView; canSubmit: boolean }>()
const orders = ref<CustomerOrder[]>([])
const inventory = ref<CustomerInventory[]>([])
const prices = ref<PartPrice[]>([])
const keyword = ref('')
const search = ref('')
const page = ref(1)
const total = ref(0)
const loading = ref(false)
const error = ref('')
const removingOrder = ref<CustomerOrder | null>(null)
let listVersion = 0

const statuses: Record<string, { label: string; tone: 'info' | 'warning' | 'success' | 'danger' }> = {
  PENDING: { label: '待确认', tone: 'info' },
  PREPARING: { label: '备货中', tone: 'warning' },
  PARTIAL_SHIPPED: { label: '部分已发货', tone: 'warning' },
  SHIPPED: { label: '已发货', tone: 'success' },
  CANCELLED: { label: '已取消', tone: 'info' },
  CLOSED: { label: '已关闭', tone: 'info' },
}
const title = computed(() => ({
  'customer-orders': '我的订单',
  'customer-inventory': '当前库存',
  'customer-part-prices': '可购零件',
})[props.view])

async function load() {
  const version = ++listVersion
  loading.value = true
  error.value = ''
  try {
    const currentPage = page.value
    if (props.view === 'customer-orders') {
      const { data } = await api.get<{ data: Page<CustomerOrder> }>('/api/customer/order-status', { params: { page: currentPage } })
      if (version !== listVersion) return
      const lastPage = Math.max(1, Math.ceil(data.data.total / data.data.pageSize))
      if (currentPage > lastPage) {
        page.value = lastPage
        await load()
        return
      }
      orders.value = data.data.records
      total.value = data.data.total
    } else if (props.view === 'customer-inventory') {
      const { data } = await api.get<{ data: Page<CustomerInventory> }>('/api/customer/inventory-status', { params: { page: currentPage } })
      if (version !== listVersion) return
      inventory.value = data.data.records
      total.value = data.data.total
    } else {
      const { data } = await api.get<{ data: Page<PartPrice> }>('/api/customer/part-prices', {
        params: { page: currentPage, keyword: search.value },
      })
      if (version !== listVersion) return
      prices.value = data.data.records
      total.value = data.data.total
    }
  } catch (cause) {
    if (version !== listVersion) return
    error.value = customerStatusError(cause, '请求失败，请重试')
  } finally {
    if (version === listVersion) loading.value = false
  }
}

function isSameOrder(order: CustomerOrder | undefined | null, target: CustomerOrder) {
  return order?.kind === target.kind && order.id === target.id
}

async function removeOrder(order: CustomerOrder) {
  if (removingOrder.value || order.status !== 'CANCELLED') return
  removingOrder.value = order
  try {
    await ElMessageBox.confirm(
      tr('订单 {orderNo} 将从你的订单列表中移除。', { orderNo: order.orderNo }),
      tr('删除订单'),
      {
        type: 'warning',
        confirmButtonText: tr('删除'),
        confirmButtonClass: 'el-button--danger',
        cancelButtonText: tr('取消'),
        closeOnClickModal: false,
      },
    )
    await api.delete('/api/customer/order-status', { params: { kind: order.kind, id: order.id } })
    ElMessage.success(tr('订单已删除'))
    if (props.view === 'customer-orders') {
      orders.value = orders.value.filter(row => !isSameOrder(row, order))
      await load()
    }
  } catch (cause) {
    if (cause === 'cancel' || cause === 'close') return
    ElMessage.error(tr(customerStatusError(cause, '删除订单失败，请重试')))
  } finally {
    removingOrder.value = null
  }
}

function findParts() {
  search.value = keyword.value.trim()
  page.value = 1
  void load()
}

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref<CustomerOrderDetail | null>(null)
let detailVersion = 0

async function showDetail(order: CustomerOrder) {
  const version = ++detailVersion
  detailVisible.value = true
  detailLoading.value = true
  detailError.value = ''
  detail.value = null
  try {
    const { data } = await api.get<{ data: CustomerOrderDetail }>('/api/customer/order-detail', {
      params: { kind: order.kind, id: order.id },
    })
    if (version === detailVersion) detail.value = data.data
  } catch (cause) {
    if (version === detailVersion) detailError.value = customerStatusError(cause, '订单详情加载失败')
  } finally {
    if (version === detailVersion) detailLoading.value = false
  }
}

type Model = { id: number; name: string; availableQty: number }
const models = ref<Model[]>([])
const submitting = ref(false)
const submitVisible = ref(false)
const submitLoading = ref(false)
const form = reactive({ requestId: createUuid(), modelId: undefined as number | undefined, quantity: 1, plannedShipDate: '', remark: '' })

async function openOrder() {
  Object.assign(form, { requestId: createUuid(), modelId: undefined, quantity: 1, plannedShipDate: '', remark: '' })
  submitVisible.value = true
  submitLoading.value = true
  try {
    const { data } = await api.get<{ data: Model[] }>('/api/customer/models')
    models.value = data.data
  } catch (cause) {
    models.value = []
    ElMessage.error(tr(customerStatusError(cause, '车型加载失败')))
  } finally {
    submitLoading.value = false
  }
}

async function submit() {
  if (submitting.value || submitLoading.value) return
  if (!form.modelId) {
    ElMessage.warning(tr('请选择车型'))
    return
  }
  submitting.value = true
  try {
    await api.post('/api/customer/orders', { ...form, plannedShipDate: form.plannedShipDate || null })
    submitVisible.value = false
    ElMessage.success(tr('订单已提交，等待业务确认'))
    page.value = 1
    await load()
  } catch (cause) {
    ElMessage.error(tr(customerStatusError(cause, '请求失败，请重试')))
  } finally {
    submitting.value = false
  }
}

watch(() => props.view, () => {
  detailVersion++
  detailVisible.value = false
  submitVisible.value = false
  keyword.value = ''
  search.value = ''
  orders.value = []
  inventory.value = []
  prices.value = []
  page.value = 1
  total.value = 0
  void load()
}, { immediate: true })

onBeforeUnmount(() => { listVersion++; detailVersion++ })
</script>

<template>
  <section class="customer-workspace">
    <header class="customer-heading">
      <h1>{{ tr(title) }}</h1>
      <div class="customer-actions">
        <el-button :loading="loading" @click="load">{{ tr('刷新') }}</el-button>
        <el-button v-if="view === 'customer-orders' && canSubmit" type="primary" @click="openOrder">{{ tr('提交订单') }}</el-button>
      </div>
    </header>
    <form v-if="view === 'customer-part-prices'" class="customer-search" @submit.prevent="findParts">
      <el-input v-model="keyword" :maxlength="128" :aria-label="tr('零件编号或名称')" :placeholder="tr('零件编号或名称')" clearable @clear="findParts" />
      <el-button type="primary" native-type="submit">{{ tr('搜索') }}</el-button>
    </form>
    <el-alert v-if="error" :title="tr(error)" type="error" :closable="false" show-icon />
    <el-table v-if="view === 'customer-orders'" :data="orders" v-loading="loading" :empty-text="tr('暂无订单')">
      <el-table-column prop="orderNo" :label="tr('订单编号')" min-width="145">
        <template #default="{ row }"><el-button link type="primary" @click="showDetail(row)">{{ row.orderNo }}</el-button></template>
      </el-table-column>
      <el-table-column prop="modelName" :label="tr('车型')" min-width="120" />
      <el-table-column prop="orderQty" :label="tr('订购数量')" min-width="85" align="right" />
      <el-table-column :label="tr('当前状态')" min-width="108">
        <template #default="{ row }"><el-tag :type="statuses[row.status]?.tone ?? 'info'">{{ tr(statuses[row.status]?.label ?? '待确认') }}</el-tag></template>
      </el-table-column>
      <el-table-column :label="tr('操作')" width="90">
        <template #default="{ row }">
          <el-button v-if="row.status === 'CANCELLED'" link type="danger"
            :loading="isSameOrder(removingOrder, row)" :disabled="removingOrder !== null"
            @click="removeOrder(row)">{{ tr('删除') }}</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-table v-else-if="view === 'customer-inventory'" :data="inventory" v-loading="loading" :empty-text="tr('暂无可展示车型')">
      <el-table-column prop="modelName" :label="tr('车型')" min-width="155" />
      <el-table-column :label="tr('当前库存数量')" min-width="125" align="right">
        <template #default="{ row }">{{ row.quantity }} {{ tr('台') }}</template>
      </el-table-column>
    </el-table>
    <el-table v-else :data="prices" v-loading="loading" :empty-text="tr('暂无可购零件')">
      <el-table-column prop="partNo" :label="tr('零件编号')" min-width="125" />
      <el-table-column :label="tr('零件名称')" min-width="94">
        <template #default="{ row }">{{ tr(row.partName) }}</template>
      </el-table-column>
      <el-table-column prop="unitPrice" :label="tr('单价')" min-width="74" align="right" />
      <el-table-column prop="currency" :label="tr('币种')" width="72" />
    </el-table>
    <el-pagination v-if="total > 20" v-model:current-page="page" :total="total" :page-size="20" layout="prev, pager, next" @current-change="load" />

    <el-dialog v-model="detailVisible" :title="tr('订单详情')" width="min(650px, 94vw)">
      <div v-loading="detailLoading" class="customer-detail">
        <el-alert v-if="detailError" :title="tr(detailError)" type="error" :closable="false" show-icon />
        <template v-if="detail">
          <dl class="detail-fields">
            <div><dt>{{ tr('订单编号') }}</dt><dd>{{ detail.order.orderNo }}</dd></div>
            <div><dt>{{ tr('车型') }}</dt><dd>{{ detail.order.modelName ?? '—' }}</dd></div>
            <div><dt>{{ tr('订购数量') }}</dt><dd>{{ detail.order.orderQty }}</dd></div>
            <div><dt>{{ tr('已发数量') }}</dt><dd>{{ detail.order.shippedQty }}</dd></div>
            <div><dt>{{ tr('当前状态') }}</dt><dd>{{ tr(statuses[detail.order.status]?.label ?? '待确认') }}</dd></div>
          </dl>
          <h3>{{ tr('物流信息') }}</h3>
          <p v-if="!detail.logistics.length">{{ tr('暂无已登记的物流信息') }}</p>
          <ul v-else class="shipment-list">
            <li v-for="(shipment, index) in detail.logistics" :key="shipment.batchNo + '-' + index">
              <strong>{{ shipment.carrier || tr('物流批次') }} · {{ shipment.batchNo }}</strong>
              <span>{{ tr(shipment.status === 'SHIPPED' ? '已发货' : '待发货') }}</span>
              <span v-if="shipment.trackingNo">{{ tr('运单号') }} {{ shipment.trackingNo }}</span>
              <time v-if="shipment.shippedAt" :datetime="shipment.shippedAt">{{ shipment.shippedAt.replace('T', ' ') }}</time>
            </li>
          </ul>
        </template>
      </div>
    </el-dialog>

    <el-dialog v-model="submitVisible" :title="tr('提交订单')" width="min(560px, 94vw)"
      :close-on-click-modal="!submitting" :close-on-press-escape="!submitting" :show-close="!submitting">
      <el-form label-position="top" @submit.prevent="submit">
        <p>{{ tr('提交订单后不会自动锁定库存，最终数量以业务确认为准。') }}</p>
        <el-form-item :label="tr('车型')" required>
          <el-select v-model="form.modelId" filterable :placeholder="tr('请选择车型')" :loading="submitLoading" :disabled="submitting">
            <el-option v-for="item in models" :key="item.id" :value="item.id" :label="tr('{name}（可订 {quantity} 台）', { name: item.name, quantity: item.availableQty })" />
          </el-select>
        </el-form-item>
        <el-form-item :label="tr('订购数量')" required><el-input-number v-model="form.quantity" :min="1" :max="100000" :precision="0" :disabled="submitting" /></el-form-item>
        <el-form-item :label="tr('期望发货日期')"><el-date-picker v-model="form.plannedShipDate" type="date" value-format="YYYY-MM-DD" :disabled="submitting" /></el-form-item>
        <el-form-item :label="tr('备注')"><el-input v-model="form.remark" type="textarea" :maxlength="500" show-word-limit :disabled="submitting" /></el-form-item>
        <div class="customer-actions">
          <el-button :disabled="submitting" @click="submitVisible = false">{{ tr('取消') }}</el-button>
          <el-button type="primary" native-type="submit" :loading="submitting" :disabled="submitLoading">{{ tr('确认提交') }}</el-button>
        </div>
      </el-form>
    </el-dialog>
  </section>
</template>

<style scoped>
.customer-workspace { display: grid; gap: 16px; min-width: 0; }
.customer-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.customer-heading h1 { margin: 0; font-size: clamp(20px, 3vw, 26px); }
.customer-actions { display: flex; justify-content: flex-end; flex-wrap: wrap; }
.customer-search { display: flex; gap: 8px; max-width: 420px; }
.customer-detail { min-height: 72px; }
.detail-fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(128px, 1fr)); gap: 12px; margin: 0 0 20px; }
.detail-fields dt { color: var(--el-text-color-secondary); }
.detail-fields dd { margin: 3px 0 0; overflow-wrap: anywhere; }
.shipment-list { display: grid; gap: 8px; list-style: none; margin: 0; padding: 0; }
.shipment-list li { display: flex; flex-wrap: wrap; gap: 6px 14px; padding: 10px 0; border-bottom: 1px solid var(--el-border-color-light); overflow-wrap: anywhere; }
</style>
