<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import api from '../api'
import { piApi, type PiLogisticsAttachment } from '../pi-api'
import BusinessAttachmentUpload from './BusinessAttachmentUpload.vue'
const props = defineProps<{ piId: number; canUpload: boolean; logisticsId: number }>()
const rows = ref<PiLogisticsAttachment[]>([]), error = ref(''), loading = ref(false), uploading = ref(false), scope = ref('CUSTOMER')
const endpoint = computed(() => `/api/pis/${props.piId}/logistics/${props.logisticsId}/attachments`)
let version = 0
function failure(e: unknown) { return api.isAxiosError(e) ? e.response?.data?.message || '操作失败，请重试' : '操作失败，请重试' }
async function load() { const n = version; loading.value = true; error.value = ''; try { const data = await piApi.logisticsAttachments(props.piId, props.logisticsId); if (n === version) rows.value = data } catch (e) { if (n === version) error.value = failure(e) } finally { if (n === version) loading.value = false } }
function saved(row: PiLogisticsAttachment) { if (!rows.value.some(item => item.id === row.id)) rows.value.push(row) }
async function download(row: PiLogisticsAttachment) {
  if (loading.value) return
  const n = version; loading.value = true; error.value = ''
  try { const blob = await piApi.downloadLogisticsAttachment(props.piId, props.logisticsId, row.id); if (n !== version) return; const url = URL.createObjectURL(blob), a = document.createElement('a'); a.href = url; a.download = row.fileName; document.body.append(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000) }
  catch (e) { if (n === version) error.value = '下载或完整性校验失败，请刷新后重试' } finally { if (n === version) loading.value = false }
}
watch(() => [props.piId, props.logisticsId], () => { version++; rows.value = []; error.value = ''; void load() }, { immediate: true })
onBeforeUnmount(() => { version++ })
</script>
<template><section><p>附件属于当前物流记录。物流撤回后保留已有文件，不能继续追加。</p><template v-if="canUpload"><el-select v-model="scope" :disabled="uploading" style="width:160px"><el-option value="CUSTOMER" label="客户可见" /><el-option value="INTERNAL" label="仅内部可见" /></el-select><BusinessAttachmentUpload :key="endpoint" :endpoint="endpoint" :visibility="scope" @saved="saved" @busy="uploading = $event" /></template><el-alert v-if="error" :title="error" type="error" :closable="false" /><el-button :disabled="loading || uploading" @click="load">刷新</el-button><el-table :data="rows" v-loading="loading" empty-text="当前物流记录暂无附件"><el-table-column prop="fileName" label="文件" /><el-table-column label="可见范围"><template #default="s">{{ s.row.visibility === 'CUSTOMER' ? '客户可见' : '仅内部' }}</template></el-table-column><el-table-column prop="createdAt" label="上传时间" /><el-table-column><template #default="s"><el-button link :disabled="loading" @click="download(s.row)">下载</el-button></template></el-table-column></el-table></section></template>
