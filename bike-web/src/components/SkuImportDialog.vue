<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, type UploadInstance, type UploadProps, type UploadRawFile } from 'element-plus'
import api from '../api'
import AppIcon from './AppIcon.vue'

const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ imported: [] }>()
type ImportResult = { totalRows: number; successRows: number; failureRows: number; failures: { rowNumber: number; reason: string }[] }
const upload = ref<UploadInstance>()
const selectedFile = ref<File>()
const importing = ref(false), downloading = ref(false), errorMessage = ref('')
const result = ref<ImportResult>()
const busy = computed(() => importing.value || downloading.value)
const fileSize = computed(() => !selectedFile.value ? '' : selectedFile.value.size < 1024 * 1024
  ? `${(selectedFile.value.size / 1024).toFixed(1)} KB` : `${(selectedFile.value.size / 1024 / 1024).toFixed(1)} MB`)

function clearFile() {
  upload.value?.clearFiles()
  selectedFile.value = undefined
  result.value = undefined
  errorMessage.value = ''
}
function selectFile(file: UploadRawFile) {
  clearFile()
  if (!/\.(xlsx|xls)$/i.test(file.name)) errorMessage.value = '请选择 .xlsx 或 .xls 文件'
  else if (file.size === 0 || file.size > 10 * 1024 * 1024) errorMessage.value = '文件不能为空且不能超过10MB'
  else selectedFile.value = file
}
const onChange: UploadProps['onChange'] = file => { if (file.raw) selectFile(file.raw) }
const onExceed: UploadProps['onExceed'] = files => { if (files[0]) selectFile(files[0] as UploadRawFile) }
function beforeClose(done: () => void) { if (!busy.value) done() }
async function messageFrom(error: unknown) {
  if (!api.isAxiosError(error)) return error instanceof Error ? error.message : '请求失败，请稍后重试'
  const data = error.response?.data
  // Template requests use a Blob response even when the server returns a JSON error.
  if (data instanceof Blob) {
    try { return JSON.parse(await data.text()).message || '模板下载失败，请稍后重试' } catch { return '模板下载失败，请稍后重试' }
  }
  return data?.message || '请求失败，请检查网络后重试'
}
async function downloadTemplate() {
  if (busy.value) return
  downloading.value = true
  errorMessage.value = ''
  try {
    const blob = (await api.get('/api/production/skus/import-template', { responseType: 'blob' })).data
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = 'sku-template.xlsx'
    anchor.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (error) {
    errorMessage.value = await messageFrom(error)
    ElMessage.error(errorMessage.value)
  } finally { downloading.value = false }
}
async function importSkus() {
  if (!selectedFile.value || busy.value) return
  importing.value = true
  errorMessage.value = ''
  result.value = undefined
  try {
    const form = new FormData()
    form.append('file', selectedFile.value)
    const imported = (await api.post<{ data: ImportResult }>('/api/production/skus/import', form)).data.data
    result.value = imported
    const summary = `导入完成：成功 ${imported.successRows} 条，失败 ${imported.failureRows} 条`
    emit('imported')
    if (imported.failureRows) ElMessage.warning(summary)
    else { ElMessage.success(summary); visible.value = false }
  } catch (error) {
    errorMessage.value = await messageFrom(error)
    ElMessage.error(errorMessage.value)
  } finally { importing.value = false }
}
</script>

<template>
  <el-dialog v-model="visible" title="导入 SKU" width="640px" align-center class="sku-import-dialog" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" :before-close="beforeClose" @closed="clearFile">
    <p class="sku-import-subtitle">请使用标准模板填写 SKU 数据后上传导入</p>
    <div class="sku-template-card">
      <span class="sku-template-icon"><AppIcon name="orders" /></span>
      <div><strong>SKU 导入模板</strong><p>请先下载模板，按模板格式填写商品 SKU 数据。</p></div>
      <el-button :loading="downloading" :disabled="importing" @click="downloadTemplate"><AppIcon name="download" />下载导入模板</el-button>
    </div>
    <el-upload ref="upload" class="sku-import-upload" drag action="#" accept=".xlsx,.xls" :auto-upload="false" :show-file-list="false" :limit="1" :disabled="busy" :on-change="onChange" :on-exceed="onExceed">
      <AppIcon name="upload" />
      <strong>点击选择文件或拖拽文件到此处</strong>
      <span>支持 .xlsx、.xls 文件，单个文件不超过 10MB</span>
    </el-upload>
    <div v-if="selectedFile" class="sku-selected-file">
      <AppIcon name="orders" /><div><strong :title="selectedFile.name">{{ selectedFile.name }}</strong><span>{{ fileSize }} · 重新选择文件可替换</span></div>
      <el-button link type="danger" :disabled="busy" @click="clearFile">删除</el-button>
    </div>
    <p class="sku-import-note"><AppIcon name="info" />每次最多导入 5000 行。选择文件后，点击“开始导入”提交。</p>
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon :closable="false" />
    <div v-if="result" class="sku-import-result" aria-live="polite">
      <el-alert :title="`导入完成：成功 ${result.successRows} 条，失败 ${result.failureRows} 条`" :type="result.failureRows ? 'warning' : 'success'" :closable="false" show-icon />
      <template v-if="result.failureRows">
        <p>已成功的记录已保存，请修正失败行后重新选择文件导入。</p>
        <el-table :data="result.failures" max-height="200" size="small"><el-table-column prop="rowNumber" label="行号" width="75" /><el-table-column prop="reason" label="失败原因" /></el-table>
      </template>
    </div>
    <template #footer><el-button :disabled="busy" @click="visible = false">取消</el-button><el-button type="primary" :disabled="!selectedFile || downloading" :loading="importing" @click="importSkus">{{ importing ? '正在导入…' : '开始导入' }}</el-button></template>
  </el-dialog>
</template>
