<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import api from '../api'
import { tr } from '../i18n/language'

const props = defineProps<{ partId: string | number; partSn: string }>()
const thumbnail = ref<HTMLElement>()
const inView = ref(false)
const photoUrl = ref('')
const failed = ref(false)
const previewVisible = ref(false)
let observer: IntersectionObserver | undefined

onMounted(() => {
  if (!thumbnail.value || !('IntersectionObserver' in window)) {
    inView.value = true
    return
  }
  observer = new IntersectionObserver(entries => {
    if (entries.some(entry => entry.isIntersecting)) {
      inView.value = true
      observer?.disconnect()
    }
  })
  observer.observe(thumbnail.value)
})

onBeforeUnmount(() => observer?.disconnect())
watch([() => props.partId, inView], async ([partId, visible], _previous, onCleanup) => {
  if (!visible) return
  const controller = new AbortController()
  let url = ''
  previewVisible.value = false
  photoUrl.value = ''
  failed.value = false
  onCleanup(() => {
    controller.abort()
    if (url) URL.revokeObjectURL(url)
  })
  try {
    const response = await api.get<Blob>(`/api/parts/${encodeURIComponent(partId)}/photo`, {
      responseType: 'blob', signal: controller.signal,
    })
    if (controller.signal.aborted) return
    url = URL.createObjectURL(response.data)
    photoUrl.value = url
  } catch {
    if (!controller.signal.aborted) failed.value = true
  }
})
</script>

<template>
  <span ref="thumbnail" class="part-photo">
    <button v-if="photoUrl && !failed" type="button" class="part-photo-button"
      :aria-label="tr('查看零部件图片') + ' · ' + partSn" @click="previewVisible = true">
      <img :src="photoUrl" alt="" @error="failed = true" />
    </button>
    <span v-else-if="failed" class="part-photo-status">{{ tr('图片加载失败') }}</span>
    <span v-else class="part-photo-status" :aria-label="tr('正在加载图片')">…</span>
  </span>
  <el-dialog v-if="previewVisible" v-model="previewVisible" append-to-body
    :title="tr('零部件图片') + ' · ' + partSn" width="min(720px, 92vw)">
    <img class="part-photo-preview" :src="photoUrl" :alt="partSn" />
  </el-dialog>
</template>

<style scoped>
.part-photo, .part-photo-button {
  display: inline-grid;
  place-items: center;
  vertical-align: middle;
  width: 60px;
  min-height: 44px;
}
.part-photo-button {
  padding: 2px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: var(--el-bg-color);
  cursor: zoom-in;
}
.part-photo-button img { width: 54px; height: 40px; object-fit: contain; }
.part-photo-preview { display: block; max-width: 100%; max-height: 68vh; margin: 0 auto; }
.part-photo-status { color: var(--el-text-color-secondary); white-space: normal; }
</style>
