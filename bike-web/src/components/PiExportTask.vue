<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import api from '../api'
import { piApi, type PiExportJob } from '../pi-api'
const props = defineProps<{ piId: number; canRetry: boolean; status: string }>()
const task = ref<PiExportJob | null>(null), error = ref(''), busy = ref(false)
const names: Record<string, string> = { PENDING: '排队中', PROCESSING: '生成中', READY: 'PDF及Excel已归档', FAILED: '生成失败，可重试' }
let timer: ReturnType<typeof setTimeout> | undefined, version = 0
async function load() {
  clearTimeout(timer); const current = ++version; busy.value = true; error.value = ''
  try {
    const found = await piApi.exportTask(props.piId)
    if (current !== version) return
    task.value = found
    if (found && ['PENDING', 'PROCESSING'].includes(found.status)) timer = setTimeout(load, 3000)
  } catch (e) { if (current === version) error.value = api.isAxiosError(e) ? e.response?.data?.message || '任务加载失败' : '任务加载失败' }
  finally { if (current === version) busy.value = false }
}
async function retry() {
  try {
    const answer = await ElMessageBox.prompt('请输入重新排队原因。已生成的文件会保留，单据和库存占用不会回滚。', '生成归档文件', { inputValidator: (v: string) => Boolean(v?.trim()) && v.length <= 500 ? true : '请输入1至500字原因' })
    const id = props.piId; busy.value = true; await piApi.retryExport(id, task.value?.revision ?? 0, answer.value.trim()); if (id === props.piId) await load()
  } catch (e) { if (e !== 'cancel' && e !== 'close') error.value = api.isAxiosError(e) ? e.response?.data?.message || '重试失败' : '重试失败' }
  finally { busy.value = false }
}
watch(() => props.piId, () => { task.value = null; void load() }, { immediate: true })
onBeforeUnmount(() => { version++; clearTimeout(timer) })
</script>
<template>
  <section>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="status === 'DRAFT'">草稿下载为预览；确认后自动生成并保存正式文件。</p>
    <p v-else-if="!task">暂无导出归档。历史已发布单据可由内部导出人员建立归档，来源会保留为历史快照。</p>
    <template v-if="task"><p><b>{{ names[task.status] || task.status }}</b> · 已尝试 {{ task.attempts }} 次</p><p v-if="task.sourceType === 'LEGACY_SNAPSHOT'">历史单据快照归档；不代表原始发布时间或当时已生成文件。</p><p v-if="task.errorCode">错误：{{ task.errorCode }}。请检查单据字符、存储及后台运行情况后重试。</p><el-descriptions :column="1" border><el-descriptions-item label="PDF校验值">{{ task.pdfSha256 || '待生成' }}</el-descriptions-item><el-descriptions-item label="Excel校验值">{{ task.xlsxSha256 || '待生成' }}</el-descriptions-item><el-descriptions-item label="更新时间">{{ task.updatedAt }}</el-descriptions-item></el-descriptions></template>
    <el-button :loading="busy" @click="load">刷新任务</el-button><el-button v-if="canRetry && ['CONFIRMED', 'RELEASED', 'SUPERSEDED'].includes(status) && (!task || task.status === 'FAILED')" :disabled="busy || Boolean(error)" type="primary" @click="retry">{{ task ? '重试生成' : '建立历史归档' }}</el-button>
  </section>
</template>
