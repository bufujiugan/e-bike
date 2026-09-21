<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { piApi, type PiPayment, type PiReceiptAttachment } from '../pi-api'

const props = defineProps<{ modelValue: boolean; piId: number; payment: PiPayment; canUpload: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const rows = ref<PiReceiptAttachment[]>([]), loading = ref(false), uploading = ref(false), downloading = ref('')
const error = ref(''), file = ref<File | null>(null), input = ref<HTMLInputElement | null>(null), requestId = ref('')
let version = 0
const busy = computed(() => uploading.value || Boolean(downloading.value))
function message(failure: unknown, fallback: string): string {
  return api.isAxiosError(failure) ? failure.response?.data?.message || fallback : failure instanceof Error ? failure.message : fallback
}
function resetFile() { file.value = null; requestId.value = ''; if (input.value) input.value.value = '' }
function selectFile(event: Event) {
  error.value = ''
  const selected = (event.target as HTMLInputElement).files?.[0] ?? null
  resetFile()
  if (!selected) return
  if (!/\.(jpe?g|png|pdf)$/i.test(selected.name) || selected.size <= 0 || selected.size > 10 * 1024 * 1024) {
    error.value = '请选择不超过 10MB 的 JPG、PNG 或 PDF 凭证'; return
  }
  file.value = selected; requestId.value = crypto.randomUUID()
}
async function load() {
  const current = version, piId = props.piId, paymentId = props.payment.id
  loading.value = true; error.value = ''
  try { const result = await piApi.receiptAttachments(piId, paymentId); if (current === version) rows.value = result }
  catch (failure) { if (current === version) error.value = message(failure, '凭证列表加载失败，请重试') }
  finally { if (current === version) loading.value = false }
}
async function upload() {
  if (!props.canUpload || props.payment.status !== 'CONFIRMED' || !file.value || busy.value || loading.value) return
  const current = version, piId = props.piId, paymentId = props.payment.id, selected = file.value
  uploading.value = true; error.value = ''
  try {
    const saved = await piApi.uploadReceipt(piId, paymentId, requestId.value, selected)
    if (current !== version) return
    if (!rows.value.some(row => row.id === saved.id)) rows.value.push(saved)
    resetFile(); ElMessage.success('凭证已绑定到这笔到账记录')
  } catch (failure) { if (current === version) error.value = message(failure, '上传失败，已保留选中文件，可直接重试') }
  finally { if (current === version) uploading.value = false }
}
async function download(row: PiReceiptAttachment) {
  if (busy.value) return
  const current = version, piId = props.piId, paymentId = props.payment.id
  downloading.value = row.id; error.value = ''
  try {
    const blob = await piApi.downloadReceipt(piId, paymentId, row.id)
    if (current !== version) return
    const url = URL.createObjectURL(blob), link = document.createElement('a')
    link.href = url; link.download = row.fileName; document.body.appendChild(link); link.click(); link.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (failure) {
    if (current !== version) return
    if (api.isAxiosError(failure) && failure.response?.data instanceof Blob) {
      try { const data = JSON.parse(await failure.response.data.text()); if (current === version) error.value = data.message || '凭证下载失败' }
      catch { if (current === version) error.value = '凭证下载失败' }
    } else error.value = message(failure, '凭证下载失败')
  } finally { if (current === version) downloading.value = '' }
}
watch(() => [props.modelValue, props.piId, props.payment.id] as const, () => {
  version++; rows.value = []; error.value = ''; loading.value = false; uploading.value = false; downloading.value = ''; resetFile()
  if (props.modelValue) void load()
}, { immediate: true })
</script>

<template>
  <el-dialog v-model="visible" title="收款凭证" width="min(800px, 95vw)" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy">
    <p>到账时间：{{ payment.receivedAt }} · 金额：{{ payment.currencyCode }} {{ payment.amount }}</p>
    <p v-if="payment.voucherObjectKey" class="receipt-hint">这笔收款保留了早期凭证引用，请核对并补传原文件。可下载凭证以本列表为准。</p>
    <div v-if="canUpload && payment.status === 'CONFIRMED'" class="receipt-upload">
      <label>选择凭证（JPG / PNG / PDF，单文件最多 10MB）<input ref="input" type="file" accept=".jpg,.jpeg,.png,.pdf" aria-label="选择收款凭证" :disabled="busy || loading" @change="selectFile" /></label>
      <p v-if="file">已选：{{ file.name }}</p>
      <el-button type="primary" :loading="uploading" :disabled="!file || loading || Boolean(downloading)" @click="upload">上传到这笔到账</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-table v-loading="loading" :data="rows" empty-text="这笔到账尚无已上传凭证">
      <el-table-column prop="fileName" label="凭证文件" min-width="210" />
      <el-table-column label="大小" width="100"><template #default="scope">{{ Math.max(1, Math.ceil(scope.row.fileSize / 1024)) }} KB</template></el-table-column>
      <el-table-column prop="createdAt" label="上传时间" min-width="180" />
      <el-table-column label="操作" width="90"><template #default="scope"><el-button link type="primary" :loading="downloading === scope.row.id" :disabled="busy && downloading !== scope.row.id" @click="download(scope.row)">下载</el-button></template></el-table-column>
    </el-table>
    <template #footer><el-button :disabled="busy || loading" @click="load">刷新列表</el-button><el-button :disabled="busy" @click="visible = false">关闭</el-button></template>
  </el-dialog>
</template>

<style scoped>
.receipt-hint{color:#65758a;font-size:13px;line-height:1.7}.receipt-upload{padding:16px;background:#f6f9fc;margin:16px 0}.receipt-upload label{display:block;font-size:13px}.receipt-upload input{display:block;margin:12px 0;max-width:100%}.receipt-upload p{font-size:13px;overflow-wrap:anywhere}.el-alert{margin:12px 0}
</style>
