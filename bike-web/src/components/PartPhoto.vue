<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import api from '../api'
import { tr } from '../i18n/language'
import AppIcon from './AppIcon.vue'

const props = defineProps<{
  partId?: string | number
  partSn: string
  src?: string
  revision?: readonly string[]
  editor?: boolean
}>()
const thumbnail = ref<HTMLElement>()
const inView = ref(false)
const photoUrl = ref('')
const failed = ref(false)
const previewVisible = ref(false)
const imageUrl = computed(() => props.src || photoUrl.value)
let observer: IntersectionObserver | undefined

onMounted(() => {
  if (props.editor || !thumbnail.value || !('IntersectionObserver' in window)) {
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
watch(() => props.src, () => {
  failed.value = false
  previewVisible.value = false
})
watch([() => props.partId, () => props.revision, inView], async ([partId, , visible], _previous, onCleanup) => {
  if (!visible || !partId) return
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
  <span ref="thumbnail" class="part-photo" :class="{ 'part-photo--editor': editor }">
    <button v-if="imageUrl && !failed" type="button" class="part-photo-button"
      :aria-label="partSn ? tr('查看零部件图片') + ' · ' + partSn : tr('查看零部件图片')" @click="previewVisible = true">
      <img :src="imageUrl" alt="" @error="failed = true" />
      <span v-if="!editor" class="part-photo-action">{{ tr('预览') }}</span>
    </button>
    <span v-else-if="failed" class="part-photo-status">{{ tr('图片加载失败') }}</span>
    <span v-else class="part-photo-status" :aria-label="tr('正在加载图片')"><AppIcon name="image" /></span>
  </span>
  <el-dialog v-if="previewVisible" v-model="previewVisible" append-to-body
    :title="partSn ? tr('零部件图片') + ' · ' + partSn : tr('零部件图片')" width="min(720px, 92vw)">
    <img class="part-photo-preview" :src="imageUrl" :alt="partSn || tr('零部件图片')" />
  </el-dialog>
</template>

<style scoped>
.part-photo {
  display: inline-flex;
  align-items: center;
  vertical-align: middle;
  min-height: 44px;
}
.part-photo-button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 44px;
  padding: 2px 6px 2px 2px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: var(--el-color-primary);
  font: inherit;
  cursor: zoom-in;
}
.part-photo-button:hover { background: var(--el-color-primary-light-9); border-color: var(--el-color-primary-light-7); }
.part-photo-button:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.part-photo-button img {
  width: 46px;
  height: 38px;
  object-fit: contain;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}
.part-photo-action { font-weight: 600; }
.part-photo--editor, .part-photo--editor .part-photo-button { width: 76px; height: 68px; justify-content: center; }
.part-photo--editor .part-photo-button { padding: 2px; }
.part-photo--editor .part-photo-button img { width: 100%; height: 100%; }
.part-photo-preview { display: block; max-width: 100%; max-height: 68vh; margin: 0 auto; }
.part-photo-status { color: var(--el-text-color-regular); white-space: normal; }
.part-photo-status .app-icon { width: 20px; height: 20px; }
</style>
