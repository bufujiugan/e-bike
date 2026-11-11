<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { createUuid } from '../utils/uuid'
type Model = {id: number; name: string; availableQty: number}
type Order = { id: number; orderNo: string; modelName: string; orderQty: number; shippedQty: number; status: string; plannedShipDate: string | null; createdAt: string; remark: string | null }
const company = ref(''), models = ref<Model[]>([]), rows = ref<Order[]>([]), total = ref(0), page = ref(1)
const loading = ref(false), catalogLoading = ref(false), saving = ref(false), error = ref(''), visible = ref(false)
const form = reactive({ requestId: createUuid(), modelId: undefined as number | undefined, quantity: 1, plannedShipDate: '', remark: '' })
const statuses: Record<string,string> = {PENDING:'待业务确认',DRAFT:'草稿',CONFIRMED:'待出库',PARTIAL_SHIPPED:'部分出库',SHIPPED:'已完成',CANCELLED:'已作废',CLOSED:'已关闭'}
function message(cause: unknown) { return api.isAxiosError(cause) ? cause.response?.data?.message ?? '请求失败，请重试' : '请求失败，请重试' }
async function load() {
  loading.value = true; error.value = ''
  try {
    const response = (await api.get('/api/customer/orders', {params:{page:page.value}})).data.data
    rows.value = response.records; total.value = response.total
  } catch (cause) { error.value = message(cause); rows.value = [] }
  finally { loading.value = false }
}
async function loadCatalog() {
  catalogLoading.value = true
  try { models.value = (await api.get('/api/customer/models')).data.data }
  catch (cause) { error.value = message(cause) }
  finally { catalogLoading.value = false }
}
onMounted(async () => {
  try {
    const profile = await api.get('/api/customer/company')
    company.value = profile.data.data.name
    await Promise.all([loadCatalog(), load()])
  } catch (cause) { error.value = message(cause) }
})
function openOrder() { Object.assign(form,{requestId:createUuid(),modelId:undefined,quantity:1,plannedShipDate:'',remark:''}); visible.value = true }
async function submit() {
  if (saving.value) return
  if (!form.modelId) { ElMessage.warning('请选择车型'); return }
  saving.value = true
  try {
    await api.post('/api/customer/orders',{...form, plannedShipDate:form.plannedShipDate || null})
    visible.value = false; page.value = 1; ElMessage.success('订单已提交，等待业务确认'); await load()
  } catch (cause) { ElMessage.error(message(cause)) }
  finally { saving.value = false }
}
</script>
<template>
  <section class="customer-workspace">
    <header><div><span class="eyebrow">客户工作台</span><h1>{{ company || '我的订单' }}</h1><p>提交订购需求，查看本公司订单及出库进度。</p></div><el-button type="primary" :disabled="!company" @click="openOrder">＋ 提交订单</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div class="customer-note">可订库存会随订单和出入库变化，仅供下单参考；提交订单后不会自动锁定库存，最终数量以业务确认为准。</div>
    <div class="customer-orders catalog"><div class="orders-heading"><h2>可订车型 <small>{{ models.length }} 款</small></h2><el-button :loading="catalogLoading" @click="loadCatalog">刷新库存</el-button></div>
      <el-table :data="models" v-loading="catalogLoading" empty-text="暂无可展示车型">
        <el-table-column prop="name" label="车型" min-width="200" />
        <el-table-column label="当前可订库存" width="170" align="right"><template #default="{row}"><strong>{{ row.availableQty }}</strong> 台</template></el-table-column>
        <el-table-column label="状态" width="130"><template #default="{row}"><el-tag :type="row.availableQty > 0 ? 'success' : 'info'">{{ row.availableQty > 0 ? '可订' : '暂时无现货' }}</el-tag></template></el-table-column>
      </el-table>
    </div>
    <div class="customer-orders"><div class="orders-heading"><h2>本公司订单 <small>{{ total }} 笔</small></h2><el-button :loading="loading" @click="load">刷新</el-button></div>
    <el-table :data="rows" v-loading="loading" empty-text="暂无订单，点击“提交订单”开始订购">
      <el-table-column prop="orderNo" label="订单编号" min-width="235" />
      <el-table-column prop="modelName" label="车型" min-width="150" />
      <el-table-column prop="orderQty" label="订购数量" width="100" />
      <el-table-column prop="shippedQty" label="已出库" width="90" />
      <el-table-column label="状态" min-width="125"><template #default="{row}"><el-tag>{{ statuses[row.status] || row.status }}</el-tag></template></el-table-column>
      <el-table-column prop="plannedShipDate" label="期望发货日期" min-width="130" />
      <el-table-column prop="remark" label="备注" min-width="160" />
      <el-table-column prop="createdAt" label="提交时间" min-width="180" />
    </el-table><el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next, total" @current-change="load" /></div>
    <el-dialog v-model="visible" title="提交订单" width="min(560px,94vw)" :close-on-click-modal="false" :before-close="(done: () => void) => { if (!saving) done() }">
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="客户公司">{{ company }}</el-form-item>
        <el-form-item label="车型" required><el-select v-model="form.modelId" filterable placeholder="请选择车型" :disabled="saving"><el-option v-for="item in models" :key="item.id" :label="`${item.name}（可订 ${item.availableQty} 台）`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="订购数量" required><el-input-number v-model="form.quantity" :min="1" :max="100000" :precision="0" :disabled="saving" /></el-form-item>
        <el-form-item label="期望发货日期"><el-date-picker v-model="form.plannedShipDate" type="date" value-format="YYYY-MM-DD" :disabled="saving" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :maxlength="500" show-word-limit :disabled="saving" /></el-form-item>
        <el-button :disabled="saving" @click="visible = false">取消</el-button><el-button type="primary" native-type="submit" :loading="saving">确认提交</el-button>
      </el-form>
    </el-dialog>
  </section>
</template>
<style scoped>
.customer-workspace{padding:26px;max-width:1450px;margin:auto;color:#23466a}.customer-workspace header{display:flex;align-items:center;justify-content:space-between;gap:18px;margin-bottom:24px}.eyebrow{color:#1677ff;font-size:12px}.customer-workspace h1{font-size:28px;margin:9px 0}.customer-workspace p{color:#748aa1;font-size:14px}.customer-note{padding:16px 20px;border:1px solid #d9e9fb;border-radius:8px;background:#f0f7ff;font-size:13px;margin:18px 0;color:#526d89}.customer-orders{padding:22px;background:#fff;border:1px solid #e3ebf4;border-radius:8px;margin-bottom:18px}.orders-heading{display:flex;justify-content:space-between;align-items:center;margin-bottom:15px}.orders-heading h2{font-size:17px}.orders-heading small{color:#8599ab;font-size:12px;margin-left:12px}.el-pagination{margin-top:18px;overflow:auto}@media(max-width:600px){.customer-workspace{padding:14px}.customer-workspace h1{font-size:22px}.customer-workspace header{align-items:flex-start;flex-direction:column}.customer-orders{padding:12px}}
</style>

