<script setup lang="ts">
import { createUuid } from '../utils/uuid'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import api from '../api'

const props = defineProps<{ endpoint: string; imagesOnly?: boolean; visibility?: string; disabled?: boolean }>()
const emit = defineEmits<{ saved: [attachment: any]; busy: [value: boolean] }>()
type Entry = { id: string; file: File; state: 'ready' | 'uploading' | 'saved' | 'failed'; progress: number; message: string }
const entries = ref<Entry[]>([]), error = ref(''), working = ref(false), input = ref<HTMLInputElement>()
const accept = computed(() => props.imagesOnly ? '.jpg,.jpeg,.png,.webp' : '.jpg,.jpeg,.png,.webp,.pdf,.xlsx')
const pending = computed(() => entries.value.some(row => row.state === 'ready' || row.state === 'failed'))
let generation = 0
function select(event: Event) {
  error.value = ''
  const files = Array.from((event.target as HTMLInputElement).files ?? [])
  if (entries.value.length + files.length > 20) { error.value = '每批最多20个文件，请先完成并清空当前批次'; return }
  for (const file of files) {
    const image = /\.(jpe?g|png|webp)$/i.test(file.name), document = /\.(pdf|xlsx)$/i.test(file.name)
    const limit = image ? 20 : 50
    if ((!image && (props.imagesOnly || !document)) || file.size <= 0 || file.size > limit * 1024 * 1024) {
      error.value = `${file.name}：格式或大小无效。图片最大20MB${props.imagesOnly ? '' : '，PDF/XLSX最大50MB'}`
      continue
    }
    entries.value.push({ id: createUuid(), file, state: 'ready', progress: 0, message: '' })
  }
  if (input.value) input.value.value = ''
}
async function start(selected?: Entry) {
  if (working.value || props.disabled) return
  const current = generation, endpoint = props.endpoint, visibility = props.visibility
  working.value = true; emit('busy', true); error.value = ''
  const queue = selected ? [selected] : entries.value.filter(row => row.state !== 'saved')
  try {
    // Transfer one file per batch request so every file has its own progress and retry result.
    for (const row of queue) {
      if (current !== generation || props.disabled) break
      row.state = 'uploading'; row.progress = 0; row.message = ''
      const form = new FormData(); form.append('files', row.file); form.append('requestIds', row.id)
      if (visibility) form.append('visibility', visibility)
      try {
        const response = await api.post(`${endpoint}/batch`, form, { timeout: 180000, onUploadProgress: event => {
          if (current === generation) row.progress = Math.min(99, Math.round(event.loaded / (event.total || row.file.size) * 100))
        } })
        if (current !== generation) return
        const result = response.data?.data?.find((item: any) => item.requestId === row.id)
        if (!result?.success) throw new Error(result?.message || '服务器未返回文件保存结果，请重试')
        row.state = 'saved'; row.progress = 100; row.message = '已保存'; emit('saved', result.attachment)
      } catch (failure) {
        if (current !== generation) return
        row.state = 'failed'
        row.message = api.isAxiosError(failure) ? failure.response?.data?.message || '上传失败，可单独重试' : failure instanceof Error ? failure.message : '上传失败，可单独重试'
      }
    }
  } finally { if (current === generation) { working.value = false; emit('busy', false) } }
}
function clear() { if (!working.value) { entries.value = []; error.value = ''; if (input.value) input.value.value = '' } }
watch(() => [props.endpoint, props.visibility], () => { generation++; working.value = false; emit('busy', false); clear() })
onBeforeUnmount(() => { generation++; emit('busy', false) })
</script>

<template>
  <section class="attachment-queue">
    <p>每批最多20张图片；JPG / PNG / WebP，每张最大20MB。</p>
    <input ref="input" type="file" multiple :accept="accept" :disabled="working || disabled" aria-label="选择生产图片" @change="select" />
    <div class="queue-actions"><van-button size="small" type="primary" :loading="working" :disabled="!pending || disabled" @click="start()">上传待处理图片</van-button><van-button size="small" :disabled="working || !entries.length" @click="clear">清空当前批次</van-button></div>
    <p v-if="error" role="alert">{{ error }}</p>
    <div v-for="row in entries" :key="row.id" class="queue-item">
      <span>{{ row.file.name }} · {{ Math.ceil(row.file.size / 1024) }} KB</span>
      <progress :value="row.progress" max="100" :aria-label="row.file.name + '上传进度'" />
      <span role="status">{{ row.progress }}% · {{ row.message || (row.state === 'uploading' ? '上传并校验中' : '等待上传') }}</span>
      <van-button v-if="row.state === 'failed'" size="small" :disabled="working || disabled" @click="start(row)">重试此图片</van-button>
    </div>
  </section>
</template>
<style scoped>
.attachment-queue{margin:12px 0;padding:12px;background:#f6f9fc}.attachment-queue p{font-size:13px}.attachment-queue input{max-width:100%}.queue-actions{display:flex;gap:8px;margin:12px 0;flex-wrap:wrap}.queue-item{margin:12px 0;overflow-wrap:anywhere}.queue-item>span{font-size:13px;display:block}.queue-item progress{margin:6px 0;width:100%}
</style>
