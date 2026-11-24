<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import AppIcon from './AppIcon.vue'
import PartPhoto from './PartPhoto.vue'
import { tr } from '../i18n/language'

const props = defineProps<{ partId?: number; partSn: string; disabled?: boolean; serverError?: string }>()
const change = defineModel<File | 'remove' | null>({ required: true })
const fileInput = ref<HTMLInputElement>()
const photoUrl = ref('')
const fileError = ref('')
const selectedFile = computed(() => change.value instanceof File ? change.value : null)
const showsPhoto = computed(() => !!selectedFile.value || (!!props.partId && change.value !== 'remove'))

watch(selectedFile, (file, _previous, onCleanup) => {
  photoUrl.value = file ? URL.createObjectURL(file) : ''
  const url = photoUrl.value
  if (url) onCleanup(() => URL.revokeObjectURL(url))
})

function choosePhoto(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  fileError.value = ''
  if (!/\.(jpe?g|png|webp)$/i.test(file.name) || file.size === 0) {
    fileError.value = '请选择有效的 JPG、PNG 或 WebP 图片'
  } else if (file.size > 20 * 1024 * 1024) {
    fileError.value = '图片不能超过 20 MB'
  } else {
    change.value = file
  }
}

function clearPhoto() {
  fileError.value = ''
  change.value = selectedFile.value ? null : 'remove'
}

function restorePhoto() {
  fileError.value = ''
  change.value = null
}
</script>

<template>
  <div class="photo-editor" role="group" :aria-label="tr('图片（选填）')">
    <input ref="fileInput" type="file" hidden accept=".jpg,.jpeg,.png,.webp"
      :disabled="disabled" @change="choosePhoto" />
    <div class="photo-card" :class="{ 'photo-card--empty': !showsPhoto }">
      <PartPhoto v-if="showsPhoto" :key="selectedFile ? 'selected' : 'saved'" :part-id="selectedFile ? undefined : partId"
        :part-sn="partSn" :src="photoUrl" editor />
      <div v-else class="photo-placeholder"><AppIcon name="image" /></div>
      <div class="photo-content">
        <strong class="photo-title" :title="selectedFile?.name">
          {{ selectedFile?.name || tr(change === 'remove' ? '保存后移除图片' : showsPhoto ? '当前图片' : '添加零部件图片') }}
        </strong>
        <span class="photo-hint">
          {{ tr(selectedFile ? '保存后更新图片' : showsPhoto ? '点击缩略图放大' : change === 'remove' ? '可撤销本次移除' : 'JPG、PNG、WebP，最大 20 MB') }}
        </span>
        <div class="photo-actions">
          <el-button plain type="primary" :disabled="disabled" @click="fileInput?.click()">
            <AppIcon name="upload" />{{ tr(showsPhoto ? '更换图片' : '选择图片') }}
          </el-button>
          <el-button v-if="showsPhoto" text :disabled="disabled" @click="clearPhoto">
            {{ tr(selectedFile && partId ? '撤销更换' : '移除') }}
          </el-button>
          <el-button v-else-if="change === 'remove'" text :disabled="disabled" @click="restorePhoto">
            {{ tr('撤销移除') }}
          </el-button>
        </div>
      </div>
    </div>
    <p v-if="fileError || serverError" class="photo-error" role="alert">{{ tr(fileError || serverError) }}</p>
  </div>
</template>

<style scoped>
.photo-editor { width: 100%; min-width: 0; }
.photo-card {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 12px;
  background: var(--el-fill-color-lighter);
}
.photo-card--empty { border-style: dashed; }
.photo-placeholder {
  display: grid;
  place-items: center;
  width: 64px;
  height: 64px;
  flex-shrink: 0;
  border-radius: 10px;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.photo-placeholder .app-icon { width: 26px; height: 26px; }
.photo-content { flex: 1; min-width: 0; display: grid; gap: 4px; line-height: 1.45; }
.photo-title { color: var(--el-text-color-primary); font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.photo-hint { color: var(--el-text-color-regular); font-size: 12px; }
.photo-actions { display: flex; flex-wrap: wrap; gap: 4px 8px; margin-top: 3px; }
.photo-actions .el-button { min-height: 36px; padding-inline: 10px; margin: 0; }
.photo-actions .app-icon { width: 15px; height: 15px; margin-inline-end: 5px; }
.photo-error { margin: 6px 0 0; color: var(--el-color-danger); font-size: 12px; line-height: 1.5; }
@media (pointer: coarse) { .photo-actions .el-button { min-height: 44px; } }
@media (max-width: 420px) {
  .photo-card { padding: 8px; gap: 8px; }
  .photo-placeholder { width: 44px; height: 44px; }
}
</style>
