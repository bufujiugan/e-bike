<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api'
import { DEFAULT_DECORATION, useAccountDecoration } from '../../account-decoration'

const props = defineProps<{ modelValue: boolean; username: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const decoration = useAccountDecoration()
const input = ref<HTMLInputElement>()
const draftFile = ref<File | null>(null)
const draftUrl = ref('')
const resetPending = ref(false)
const previewReady = ref(false)
const saving = ref(false)
const feedback = ref('')
const previewUrl = computed(() => resetPending.value ? DEFAULT_DECORATION : draftUrl.value || decoration?.imageUrl.value || DEFAULT_DECORATION)
const hasChange = computed(() => Boolean(draftFile.value) || resetPending.value)

function discardDraft() {
  if (draftUrl.value) URL.revokeObjectURL(draftUrl.value)
  draftUrl.value = ''
  draftFile.value = null
  resetPending.value = false
  previewReady.value = false
  feedback.value = ''
  if (input.value) input.value.value = ''
}

function chooseImage(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  target.value = ''
  if (!file || saving.value) return
  if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type)) {
    feedback.value = '请选择 PNG、JPG 或 WebP 图片。'
    return
  }
  if (file.size > 2 * 1024 * 1024) {
    feedback.value = '图片不能超过 2 MB。'
    return
  }
  discardDraft()
  draftFile.value = file
  draftUrl.value = URL.createObjectURL(file)
}

function validatePreview(event: Event) {
  if (!draftFile.value) return
  const image = event.target as HTMLImageElement
  if (image.naturalWidth > 4096 || image.naturalHeight > 4096) {
    discardDraft()
    feedback.value = '图片宽高均不能超过 4096 像素。'
    return
  }
  previewReady.value = true
}

function rejectPreview() {
  if (!draftFile.value) return
  discardDraft()
  feedback.value = '图片无法读取，请重新选择。'
}

function restoreDefault() {
  discardDraft()
  resetPending.value = true
}

async function saveImage() {
  if (!decoration || saving.value || !hasChange.value || (draftFile.value && !previewReady.value)) return
  saving.value = true
  feedback.value = ''
  try {
    if (await decoration.save(resetPending.value ? null : draftFile.value)) {
      discardDraft()
      ElMessage.success('装饰图片已保存，其他设备重新登录后也会加载。')
      emit('update:modelValue', false)
    }
  } catch (error) {
    feedback.value = api.isAxiosError(error) ? error.response?.data?.message || '保存失败，请重试。' : '保存失败，请重试。'
  } finally {
    saving.value = false
  }
}

watch(() => props.username, () => { discardDraft(); emit('update:modelValue', false) })
watch(() => props.modelValue, visible => { if (visible) discardDraft() })
onBeforeUnmount(discardDraft)
</script>

<template>
  <el-dialog :model-value="modelValue" title="更换装饰图片" width="min(560px, calc(100vw - 24px))" class="decoration-image-dialog" append-to-body :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" @update:model-value="emit('update:modelValue', $event)" @closed="discardDraft">
    <p class="decoration-description">换上喜欢的宠物、角色或自己的图片。工作台、运营页和侧栏会一起更新。</p>
    <el-alert v-if="decoration?.loadError.value" type="warning" :closable="false" :title="decoration.loadError.value"><el-button link :loading="decoration.loading.value" :disabled="saving" @click="decoration.reload">重新加载</el-button></el-alert>
    <div class="decoration-previews" v-loading="decoration?.loading.value">
      <div class="decoration-preview"><img :src="previewUrl" alt="页面装饰预览" @load="validatePreview" @error="rejectPreview" /><span>页面大图</span></div>
      <div class="decoration-preview is-small"><img :src="previewUrl" alt="侧栏装饰预览" /><span>侧栏小图</span></div>
    </div>
    <div class="decoration-upload-actions">
      <el-button :disabled="saving || decoration?.loading.value" @click="input?.click()">选择自己的图片</el-button>
      <el-button :disabled="saving || decoration?.loading.value || (!decoration?.savedImage.value && !draftFile)" @click="restoreDefault">恢复默认</el-button>
      <input ref="input" type="file" accept="image/png,image/jpeg,image/webp" hidden aria-label="选择装饰图片" :disabled="saving" @change="chooseImage" />
    </div>
    <p class="decoration-hint">支持 PNG、JPG、WebP，最大 2 MB，宽高不超过 4096 像素。等比例显示，不裁切；透明底 PNG 的贴纸效果更好。</p>
    <p class="decoration-account-note">仅对账号 <strong>{{ username }}</strong> 生效，保存后随账号同步。</p>
    <p v-if="hasChange" class="decoration-draft-note" role="status">当前为预览，点击“保存图片”后生效。</p>
    <el-alert v-if="feedback" type="error" :closable="false" :title="feedback" role="alert" />
    <template #footer><el-button :disabled="saving" @click="emit('update:modelValue', false)">取消</el-button><el-button type="primary" :loading="saving" :disabled="!hasChange || decoration?.loading.value || Boolean(draftFile && !previewReady)" @click="saveImage">保存图片</el-button></template>
  </el-dialog>
</template>

<style scoped>
.decoration-description { margin: 0 0 16px; color: var(--bike-ink); line-height: 1.8; }
.decoration-previews { display: flex; justify-content: center; align-items: end; gap: 20px; margin: 20px 0; padding: 20px; border: 1px dashed var(--bike-sticker-border); border-radius: 18px; background: var(--bike-hero); }
.decoration-preview { display: grid; justify-items: center; gap: 12px; }
.decoration-preview img { width: 140px; height: 140px; object-fit: contain; filter: drop-shadow(2px 0 0 white) drop-shadow(-2px 0 0 white) drop-shadow(0 2px 0 white); }
.decoration-preview.is-small img { width: 64px; height: 64px; }
.decoration-preview span, .decoration-hint { color: var(--bike-text-muted); font-size: 12px; line-height: 1.8; }
.decoration-upload-actions { display: flex; flex-wrap: wrap; gap: 10px; }
.decoration-upload-actions .el-button { margin: 0; min-height: 40px; }
.decoration-account-note { color: var(--bike-text-muted); font-size: 13px; }
.decoration-draft-note { color: var(--bike-primary); font-size: 13px; }
@media (max-width: 420px) { .decoration-previews { padding: 16px 8px; gap: 16px; } }
</style>
