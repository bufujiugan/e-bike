<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import api from '../api'
const emit = defineEmits<{order:[orderNo:string]}>()
type Notice = {id:number;orderNo:string;customerName:string;modelName:string;orderQty:number;plannedShipDate:string|null;remark:string|null;createdAt:string}
const notices=ref<Notice[]>([]),visible=ref(false),error=ref(''),saving=ref(false)
const shown = new Set<number>()
let timer: ReturnType<typeof setTimeout> | undefined, disposed=false, busy=false
async function load() {
  if(busy||disposed)return
  busy=true
  try {
    const rows=(await api.get('/api/customer-order-notifications')).data.data as Notice[]
    if(disposed)return
    notices.value=rows;error.value=''
    if(rows.some(r=>!shown.has(r.id))){rows.forEach(r=>shown.add(r.id));visible.value=true}
  } catch { if(!disposed)error.value='订单提醒暂不可用，请刷新重试' }
  finally {busy=false;if(!disposed)timer=setTimeout(load,15000)}
}
async function acknowledge(notice:Notice, open:boolean) {
  if(saving.value)return
  saving.value=true
  try {
    await api.post('/api/customer-order-notifications/'+notice.id+'/read')
    if(disposed)return
    notices.value=notices.value.filter(r=>r.id!==notice.id)
    if(open){visible.value=false;emit('order',notice.orderNo)}else if(!notices.value.length)visible.value=false
  } catch {error.value='未能保存已读状态，请重试'}
  finally{saving.value=false}
}
function refresh(){if(timer)clearTimeout(timer);void load()}
onMounted(load)
onUnmounted(()=>{disposed=true;if(timer)clearTimeout(timer)})
</script>
<template>
  <div class="customer-notice-trigger"><el-badge :value="notices.length" :hidden="!notices.length"><el-button @click="visible=true;refresh()">客户新订单</el-button></el-badge></div>
  <el-dialog v-model="visible" title="客户订单提醒" width="min(640px,94vw)" :close-on-click-modal="false">
    <el-alert v-if="error" :title="error" type="warning" :closable="false" />
    <p class="notice-hint">以下客户订单等待业务确认。查看后可在订单办理中继续处理。</p>
    <el-empty v-if="!notices.length&&!error" description="暂无未读客户订单" />
    <article v-for="notice in notices" :key="notice.id" class="customer-notice">
      <h3>{{ notice.customerName }} <el-tag type="warning">待确认</el-tag></h3><p>{{ notice.orderNo }}</p>
      <dl><dt>车型 / 数量</dt><dd>{{ notice.modelName }} · {{ notice.orderQty }} 台</dd><dt>期望发货</dt><dd>{{ notice.plannedShipDate || '待协商' }}</dd><dt>备注</dt><dd>{{ notice.remark || '无' }}</dd><dt>提交时间</dt><dd>{{ notice.createdAt }}</dd></dl>
      <el-button :disabled="saving" @click="acknowledge(notice,false)">标记已读</el-button><el-button type="primary" :disabled="saving" @click="acknowledge(notice,true)">查看订单</el-button>
    </article>
    <template #footer><el-button @click="refresh">刷新</el-button><el-button @click="visible=false">稍后处理</el-button></template>
  </el-dialog>
</template>
<style scoped>
.customer-notice-trigger{display:flex;justify-content:flex-end;padding:0}.notice-hint{color:#71849c}.customer-notice{padding:18px;border:1px solid #e0eaf4;border-radius:12px;margin:12px 0}.customer-notice h3{margin:0;font-size:16px}.customer-notice p{color:#8191a2;overflow-wrap:anywhere}.customer-notice dl{display:grid;grid-template-columns:105px 1fr;gap:10px;font-size:13px}.customer-notice dt{color:#8292a2}.customer-notice dd{margin:0;overflow-wrap:anywhere}
</style>

