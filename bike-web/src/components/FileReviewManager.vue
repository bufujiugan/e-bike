<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
type Row = { objectKey: string; purpose: string; businessId: string; fileName: string; fileSize: number; state: string; objectState: string; version: number; createdName: string; createdAt: string; checkedAt: string | null; errorCode: string | null; reviewReason: string | null }
const records = ref<Row[]>([]), state = ref(''), page = ref(1), total = ref(0), busy = ref(false), error = ref('')
const historyVisible = ref(false), history = ref<Record<string, unknown>[]>([])
const labels: Record<string,string> = { PENDING: '待绑定', BOUND: '已绑定', REVIEW: '待核对', CLEANUP_PENDING: '待清理', CLEANED: '已清理', UNKNOWN: '尚未确认', PRESENT: '文件存在', MISSING: '文件不存在' }
const purposes: Record<string,string> = { PRODUCTION_ISSUE: '生产异常', SKU_IMAGE: 'SKU 图片', PI_CONFIRMATION: 'PI 确认附件', PI_RECEIPT: '收款凭证', PI_LOGISTICS: '物流附件', AFTER_SALES: '售后附件', PI_EXPORT: 'PI 归档' }
const errors: Record<string,string> = { UPLOAD_OR_BIND_FAILED: '上传或绑定未完成', UNBOUND_OBJECT: '文件存在但没有业务引用', OBJECT_MISSING: '文件已不存在，可核对后清理记录', STORAGE_CHECK_FAILED: '存储检查失败，请重试', CLEANUP_FAILED: '文件清理失败，将重试', BOUND_OBJECT_MISSING: '已有业务引用，但文件不存在，请检查存储' }
function message(e: unknown) { return api.isAxiosError(e) ? e.response?.data?.message || '操作失败，请重试' : '操作失败，请重试' }
async function load(next = 1) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { const data = (await api.get('/api/file-reviews', { params: { state: state.value || undefined, page: next, pageSize: 20 } })).data.data; records.value = data.records; total.value = data.total; page.value = data.page }
  catch (e) { records.value = []; total.value = 0; error.value = message(e) }
  finally { busy.value = false }
}
async function act(row: Row, action: 'reconcile' | 'cleanup') {
  let reason: string
  try { const result = await ElMessageBox.prompt(action === 'cleanup' ? '清理只针对没有业务引用的文件。请输入核对结论及清理原因。' : '请输入本次核对原因。正在上传的记录会被跳过。', action === 'cleanup' ? '清理未绑定文件' : '重新核对', { inputValidator: value => !!value?.trim() && value.trim().length <= 500 || '请输入1至500字原因', confirmButtonText: '确认', cancelButtonText: '取消' }); reason = result.value.trim() }
  catch { return }
  busy.value = true; error.value = ''
  try { const updated = (await api.post(`/api/file-reviews/${action}`, { objectKey: row.objectKey, version: row.version, reason })).data.data; ElMessage.success(`当前状态：${labels[updated.state] || updated.state}`) }
  catch (e) { error.value = message(e) }
  finally { busy.value = false }
  if (!error.value) await load(page.value)
}
async function showHistory(row: Row) {
  busy.value = true; error.value = ''; history.value = []
  try { history.value = (await api.get('/api/file-reviews/history', { params: { objectKey: row.objectKey } })).data.data; historyVisible.value = true }
  catch (e) { error.value = message(e) }
  finally { busy.value = false }
}
onMounted(() => load())
</script>
<template>
  <section class="panel data-panel">
    <h2>上传失败核对</h2>
    <p>查看上传及业务绑定未完成的文件，核对后清理未绑定文件。已绑定附件保留在原业务中；此处不提供内容下载或重新绑定。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form inline @submit.prevent="load()"><el-form-item label="状态"><el-select v-model="state" style="width: 210px" :disabled="busy"><el-option label="待处理（默认）" value="" /><el-option v-for="key in ['PENDING','REVIEW','CLEANUP_PENDING','CLEANED','BOUND']" :key="key" :label="labels[key]" :value="key" /></el-select></el-form-item><el-button native-type="submit" :loading="busy">查询 / 刷新</el-button></el-form>
    <el-table :data="records" v-loading="busy" empty-text="没有匹配的上传记录">
      <el-table-column label="业务" min-width="135"><template #default="{ row }">{{ purposes[row.purpose] || row.purpose }} · {{ row.businessId }}</template></el-table-column>
      <el-table-column prop="fileName" label="文件名" min-width="170" show-overflow-tooltip />
      <el-table-column label="大小" width="100"><template #default="{ row }">{{ (row.fileSize / 1024).toFixed(1) }} KB</template></el-table-column>
      <el-table-column label="状态" min-width="110"><template #default="{ row }">{{ labels[row.state] }}<br>{{ labels[row.objectState] }}</template></el-table-column>
      <el-table-column label="核对结果" min-width="180"><template #default="{ row }">{{ errors[row.errorCode] || row.errorCode || '—' }}<br>{{ row.reviewReason || '' }}</template></el-table-column>
      <el-table-column prop="createdName" label="上传人员" min-width="100" /><el-table-column prop="createdAt" label="登记时间" min-width="175" />
      <el-table-column label="操作" width="215"><template #default="{ row }"><el-button size="small" :disabled="busy" @click="act(row, 'reconcile')">核对</el-button><el-button v-if="['REVIEW','CLEANUP_PENDING'].includes(row.state)" size="small" type="danger" :disabled="busy" @click="act(row, 'cleanup')">清理</el-button><el-button size="small" :disabled="busy" @click="showHistory(row)">历史</el-button></template></el-table-column>
    </el-table>
    <p>共 {{ total }} 条，第 {{ page }} 页。超时未绑定记录会自动进入核对；文件清理须由授权人员明确申请。</p>
    <el-button :disabled="busy || page <= 1" @click="load(page - 1)">上一页</el-button><el-button :disabled="busy || page * 20 >= total" @click="load(page + 1)">下一页</el-button>
    <el-dialog v-model="historyVisible" title="最近100条核对历史" width="850px"><el-table :data="history"><el-table-column label="操作"><template #default="{ row }">{{ row.action === 'RECONCILE' ? '核对' : '申请清理' }}</template></el-table-column><el-table-column prop="operatorName" label="人员" /><el-table-column prop="reason" label="原因" min-width="200" /><el-table-column prop="createdAt" label="时间" min-width="175" /></el-table></el-dialog>
  </section>
</template>
