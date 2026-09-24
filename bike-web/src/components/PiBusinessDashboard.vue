<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import api from '../api'
import { piApi, type PiQueueRow } from '../pi-api'
const props = defineProps<{ refresh: number }>()
const emit = defineEmits<{ openPi: [id: number] }>()
const labels: Record<string, string> = { EXPIRING_LOCKS: '24小时内到期锁定', MISSING_PI: '待补充 PI', AWAITING_PAYMENT: '待收款确认', AWAITING_SHIPMENT: '待发货', PARTIAL_SHIPMENT: '部分发货' }
const descriptions: Record<string, string> = {
  EXPIRING_LOCKS: '仍有未转换数量且未来24小时内到期的临时锁定；已到期锁定不计入。',
  MISSING_PI: '待完善的PI草稿（含修订草稿），以及有订单查看权限时尚未关联PI的有效订单。',
  AWAITING_PAYMENT: '当前已发布版本的系列累计有效到账少于单据总额；此项仅财务授权岗位可见。',
  AWAITING_SHIPMENT: '当前已发布版本尚未发货且仍有未发数量，包括尚未放行或尚未建立履约订单的单据。',
  PARTIAL_SHIPMENT: '当前已发布版本已发部分数量，且有效履约订单仍有未发数量；关闭剩余的不计为待发。',
}
const counts = ref<Record<string, number | null>>({}), rows = ref<PiQueueRow[]>([]), total = ref(0), page = ref(1), keyword = ref('')
const queue = ref(''), visible = ref(false), busy = ref(false), error = ref(''), summaryError = ref('')
let version = 0, summaryVersion = 0
function message(e: unknown) { return api.isAxiosError(e) ? e.response?.data?.message || '业务看板读取失败' : '业务看板读取失败' }
async function summary() {
  const request = ++summaryVersion; summaryError.value = ''
  try { const found = await piApi.dashboard(); if (request === summaryVersion) counts.value = found }
  catch (e) { if (request === summaryVersion) { counts.value = {}; summaryError.value = message(e) } }
}
async function load() {
  if (!queue.value || !visible.value) return
  const request = ++version; busy.value = true; error.value = ''
  try { const found = await piApi.queue(queue.value, { keyword: keyword.value.trim() || undefined, page: page.value, pageSize: 20 }); if (request === version) { rows.value = found.records; total.value = found.total } }
  catch (e) { if (request === version) { rows.value = []; total.value = 0; error.value = message(e) } }
  finally { if (request === version) busy.value = false }
}
function open(key: string) { queue.value = key; keyword.value = ''; page.value = 1; rows.value = []; visible.value = true; void load() }
function search() { page.value = 1; void load() }
watch(() => props.refresh, () => { void summary(); if (visible.value) void load() })
onMounted(summary)
</script>
<template>
  <section aria-label="业务待办看板">
    <el-alert v-if="summaryError" :title="summaryError" type="error" :closable="false"><el-button link @click="summary">重试</el-button></el-alert>
    <div class="pi-queue-cards"><template v-for="(label, key) in labels" :key="key"><button v-if="counts[key] != null" type="button" @click="open(String(key))"><span>{{ label }}</span><strong>{{ counts[key] }}</strong></button></template></div>
    <p class="pi-hint">按当前授权范围实时统计；一个单据可出现在不同待办中，点击查看全部分页明细。</p>
    <el-dialog v-model="visible" :title="labels[queue]" width="min(1050px, 95vw)">
      <p>{{ descriptions[queue] }}</p>
      <form class="pi-filters" @submit.prevent="search"><el-input v-model="keyword" placeholder="业务编号或客户名称" clearable /><el-button native-type="submit" :loading="busy">查询</el-button></form>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-table :data="rows" v-loading="busy" empty-text="没有符合条件的待办">
        <el-table-column label="类型" width="85"><template #default="{ row }">{{ { PI: 'PI', ORDER: '订单', LOCK: '锁定' }[row.recordType as PiQueueRow['recordType']] }}</template></el-table-column>
        <el-table-column prop="referenceNo" label="业务编号" min-width="220"><template #default="{ row }"><el-button v-if="row.piId" link type="primary" @click="emit('openPi', row.piId)">{{ row.referenceNo }} · R{{ row.revision }}</el-button><span v-else>{{ row.referenceNo }}</span></template></el-table-column>
        <el-table-column prop="customerName" label="客户" min-width="150" /><el-table-column prop="businessOwner" label="负责人" min-width="120" />
        <el-table-column prop="shippedQty" label="已发" width="70" /><el-table-column prop="remainingQty" label="剩余" width="70" />
        <el-table-column v-if="queue === 'EXPIRING_LOCKS'" prop="expiresAt" label="截止时间" min-width="180" />
        <el-table-column prop="createdAt" label="创建时间" min-width="180" />
      </el-table>
      <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @current-change="load" />
    </el-dialog>
  </section>
</template>
<style scoped>
.pi-queue-cards { display: flex; flex-wrap: wrap; gap: 12px; margin: 16px 0 8px; }
.pi-queue-cards button { flex: 1 1 150px; border: 1px solid #dbe2ea; border-radius: 8px; padding: 14px; text-align: left; background: #fff; color: #334155; cursor: pointer; }
.pi-queue-cards strong { display: block; font-size: 24px; margin-top: 8px; color: #1677c8; }
</style>
