<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import api from '../api'
import TraceDetails, { type TraceData } from './TraceDetails.vue'
const type = ref<'frame'|'part'|'qr'>('qr'), code = ref(''), busy = ref(false), error = ref('')
const result = ref<TraceData | null>(null)
let requestVersion = 0
watch([code,type], () => { requestVersion++; result.value = null; error.value = ''; busy.value = false })
async function search() {
  const version = ++requestVersion
  result.value = null; error.value = ''
  if (!code.value.trim()) { error.value = '请输入或扫描追溯编码'; return }
  busy.value = true
  try {
    const response = await api.get(`/api/trace/${type.value}/${encodeURIComponent(code.value.trim())}`)
    if (version === requestVersion) result.value = response.data.data
  } catch (e) {
    if (version === requestVersion) error.value = api.isAxiosError(e) ? e.response?.data?.message ?? '追溯查询失败' : '追溯查询失败'
  } finally { if (version === requestVersion) busy.value = false }
}
async function selectCode(codeValue: string, codeType: 'qr' | 'frame') { type.value = codeType; code.value = codeValue; await nextTick(); await search() }
</script>
<template>
  <section class="trace-query">
    <h2>全链路追溯</h2><p>扫描聚合码、车架 SN 或当前/历史部件 SN，查看生产、换件与出入库记录。</p>
    <form class="trace-search" @submit.prevent="search">
      <label>编码类型<select v-model="type"><option value="qr">整车聚合码</option><option value="frame">车架 SN（辅助查询）</option><option value="part">部件 SN（含历史旧件）</option></select></label>
      <label class="trace-code">追溯编码<input v-model="code" placeholder="请输入或扫描编码" autocomplete="off" /></label>
      <button type="submit" :disabled="busy">{{ busy ? '查询中…' : '开始追溯查询' }}</button>
    </form>
    <p v-if="error" role="alert" class="trace-error">{{ error }}</p>
    <TraceDetails v-if="result" :data="result" @select-code="selectCode" />
  </section>
</template>
<style scoped>
.trace-query{padding:24px;min-width:0}.trace-query>h2{font-size:22px;margin:0 0 8px}.trace-query>p{color:#64748b;font-size:14px}.trace-search{display:flex;gap:14px;align-items:end;flex-wrap:wrap;margin-top:24px}.trace-search label{display:grid;gap:8px;font-size:13px;color:#52687d}.trace-search input,.trace-search select,.trace-search button{height:44px;border-radius:7px;padding:0 14px;border:1px solid #cbd5e1;background:#fff}.trace-code{flex:1;min-width:200px}.trace-search button{background:#1677c8;color:white;border:0;cursor:pointer}.trace-search button:disabled{opacity:.6}.trace-error{color:#c52b2b!important}
</style>
