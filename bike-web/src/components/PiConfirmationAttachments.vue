<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import api from '../api'
import { piApi, type PiConfirmationAttachment } from '../pi-api'
const props = defineProps<{ piId: number; canUpload: boolean }>()
const rows = ref<PiConfirmationAttachment[]>([]), error = ref(''), busy = ref(false), file = ref<File | null>(null), scope = ref('CUSTOMER'), requestId = ref(''), input = ref<HTMLInputElement>()
let version = 0
function failure(e: unknown) { return api.isAxiosError(e) ? e.response?.data?.message || '操作失败，请重试' : '操作失败，请重试' }
async function load() { const n = version; busy.value = true; error.value = ''; try { const data = await piApi.confirmationAttachments(props.piId); if (n === version) rows.value = data } catch (e) { if (n === version) error.value = failure(e) } finally { if (n === version) busy.value = false } }
function select(event: Event) {
  file.value = null; error.value = ''; requestId.value = crypto.randomUUID()
  const chosen = (event.target as HTMLInputElement).files?.[0]
  if (!chosen) return
  if (!/\.(png|jpe?g|pdf)$/i.test(chosen.name) || chosen.size <= 0 || chosen.size > 10 * 1024 * 1024) { error.value = '请选择不超过10MB的JPG、PNG或PDF'; return }
  file.value = chosen
}
async function upload() {
  if (!props.canUpload || !file.value || busy.value) return
  const n = version; busy.value = true; error.value = ''
  try { await piApi.uploadConfirmation(props.piId, requestId.value, scope.value, file.value); if (n === version) { file.value = null; if (input.value) input.value.value = ''; await load() } }
  catch (e) { if (n === version) error.value = failure(e) } finally { if (n === version) busy.value = false }
}
async function download(row: PiConfirmationAttachment) {
  if (busy.value) return
  const n = version; busy.value = true; error.value = ''
  try { const blob = await piApi.downloadConfirmation(props.piId, row.id); if (n !== version) return; const url = URL.createObjectURL(blob), a = document.createElement('a'); a.href = url; a.download = row.fileName; document.body.append(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000) }
  catch (e) { if (n === version) error.value = '下载或完整性校验失败，请刷新后重试' } finally { if (n === version) busy.value = false }
}
watch(scope, () => { requestId.value = crypto.randomUUID() })
watch(() => props.piId, () => { version++; rows.value = []; file.value = null; error.value = ''; if (input.value) input.value.value = ''; void load() }, { immediate: true })
onBeforeUnmount(() => { version++ })
</script>
<template><section><p>附件仅属于当前PI修订；追加附件不会覆盖已归档的PDF或Excel。</p><div v-if="canUpload"><label>确认文件（JPG / PNG / PDF，最大10MB）<input ref="input" type="file" accept=".jpg,.jpeg,.png,.pdf" :disabled="busy" @change="select" /></label><el-select v-model="scope" :disabled="busy" style="width:160px;margin:12px"><el-option value="CUSTOMER" label="客户可见" /><el-option value="INTERNAL" label="仅内部可见" /></el-select><el-button :disabled="!file" :loading="busy" type="primary" @click="upload">上传确认附件</el-button></div><el-alert v-if="error" :title="error" type="error" :closable="false" /><el-button :disabled="busy" @click="load">刷新</el-button><el-table :data="rows" v-loading="busy" empty-text="当前修订暂无确认附件"><el-table-column prop="fileName" label="文件" /><el-table-column label="可见范围"><template #default="s">{{ s.row.visibility === 'CUSTOMER' ? '客户可见' : '仅内部' }}</template></el-table-column><el-table-column prop="createdAt" label="上传时间" /><el-table-column><template #default="s"><el-button link :disabled="busy" @click="download(s.row)">下载</el-button></template></el-table-column></el-table></section></template>
