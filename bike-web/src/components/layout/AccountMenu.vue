<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { DropdownInstance } from 'element-plus'
import AppIcon from '../AppIcon.vue'
import api from '../../api'
import { accountAvatarApi } from '../../account-avatar-api'

const props = defineProps<{ username: string; role: string; collapsed?: boolean }>()
const emit = defineEmits<{ settings: []; logout: [] }>()
const isOpen = ref(false)
const profileVisible = ref(false)
const dropdown = ref<DropdownInstance>()
const avatarButton = ref<HTMLButtonElement>()
const fileInput = ref<HTMLInputElement>()
const savedAvatar = ref<string | null>(null)
const draftFile = ref<File | null>(null)
const draftPreview = ref('')
const resetPending = ref(false)
const avatarLoading = ref(false)
const avatarSaving = ref(false)
const avatarError = ref('')
const previewAvatar = computed(() => resetPending.value ? null : draftPreview.value || savedAvatar.value)
const hasAvatarChange = computed(() => Boolean(draftFile.value) || resetPending.value)
let loadVersion = 0

function discardAvatarDraft() {
  if (draftPreview.value) URL.revokeObjectURL(draftPreview.value)
  draftPreview.value = ''
  draftFile.value = null
  resetPending.value = false
  if (fileInput.value) fileInput.value.value = ''
}

async function loadAvatar() {
  const version = ++loadVersion
  avatarLoading.value = true
  avatarError.value = ''
  try {
    const avatar = await accountAvatarApi.get()
    if (version === loadVersion) savedAvatar.value = avatar.imageDataUrl
  } catch {
    if (version === loadVersion) avatarError.value = '头像加载失败，请重试。'
  } finally {
    if (version === loadVersion) avatarLoading.value = false
  }
}

function chooseAvatar(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
    ElMessage.warning('请选择 JPG、PNG 或 WebP 图片')
    return
  }
  if (file.size > 2 * 1024 * 1024) {
    ElMessage.warning('头像文件不能超过 2 MB')
    return
  }
  discardAvatarDraft()
  draftFile.value = file
  draftPreview.value = URL.createObjectURL(file)
}

function previewFailed() {
  if (!draftFile.value) return
  discardAvatarDraft()
  ElMessage.warning('图片无法读取，请重新选择')
}

function resetAvatarDraft() {
  discardAvatarDraft()
  resetPending.value = true
}

async function saveAvatar() {
  if (avatarSaving.value || !hasAvatarChange.value) return
  avatarSaving.value = true
  try {
    const avatar = resetPending.value
      ? await accountAvatarApi.reset()
      : await accountAvatarApi.save(draftFile.value!)
    savedAvatar.value = avatar.imageDataUrl
    avatarError.value = ''
    discardAvatarDraft()
    ElMessage.success('头像已保存')
  } catch (error) {
    ElMessage.error(api.isAxiosError(error) ? error.response?.data?.message || '头像保存失败，请重试' : '头像保存失败，请重试')
  } finally {
    avatarSaving.value = false
  }
}

watch(() => props.username, () => {
  savedAvatar.value = null
  discardAvatarDraft()
  void loadAvatar()
}, { immediate: true })
onBeforeUnmount(() => { loadVersion++; discardAvatarDraft() })

async function closeAccountMenu() {
  dropdown.value?.handleClose()
  await nextTick()
  avatarButton.value?.focus()
}

function selectAccountAction(command: string) {
  if (command === 'profile' || command === 'avatar') profileVisible.value = true
  else if (command === 'settings') emit('settings')
  else if (command === 'logout') emit('logout')
}
</script>

<template>
  <el-dropdown ref="dropdown" trigger="click" placement="bottom-end" popper-class="sm-account-popover" :show-arrow="false" @visible-change="isOpen = $event" @command="selectAccountAction">
    <button ref="avatarButton" type="button" class="account-avatar-trigger" :class="{ 'is-open': isOpen, 'is-collapsed': collapsed }" aria-label="账号菜单" :aria-expanded="isOpen" :title="`${username} · 账号菜单`" @keydown.esc.stop.prevent="closeAccountMenu">
      <span class="account-avatar" aria-hidden="true"><img v-if="savedAvatar" :src="savedAvatar" alt="" /><template v-else>{{ username.slice(0, 1).toUpperCase() }}</template></span>
      <span class="account-trigger-identity" aria-hidden="true"><strong>{{ username }}</strong><small>{{ role }}</small></span>
      <AppIcon name="chevron" class="account-trigger-chevron" />
    </button>
    <template #dropdown>
      <div class="account-menu-panel" @keydown.esc.capture.stop.prevent="closeAccountMenu">
        <div class="account-menu-profile">
          <span class="account-avatar account-avatar--large" aria-hidden="true"><img v-if="savedAvatar" :src="savedAvatar" alt="" /><template v-else>{{ username.slice(0, 1).toUpperCase() }}</template></span>
          <div class="account-menu-identity"><strong :title="username">{{ username }}</strong><span>{{ role }}</span></div>
        </div>
        <el-dropdown-menu class="account-menu-actions">
          <el-dropdown-item command="profile"><AppIcon name="users" /><span>个人资料</span></el-dropdown-item>
          <el-dropdown-item command="avatar"><AppIcon name="upload" /><span>修改头像</span></el-dropdown-item>
          <el-dropdown-item command="settings"><AppIcon name="production" /><span>账号设置</span></el-dropdown-item>
          <el-dropdown-item command="logout"><AppIcon name="logout" /><span>退出登录</span></el-dropdown-item>
        </el-dropdown-menu>
      </div>
    </template>
  </el-dropdown>
  <el-dialog v-model="profileVisible" title="个人资料" width="min(28rem, calc(100vw - 2rem))" append-to-body :close-on-click-modal="false" :close-on-press-escape="!avatarSaving" :show-close="!avatarSaving" @closed="discardAvatarDraft">
    <el-alert v-if="avatarError" type="warning" :closable="false" :title="avatarError"><el-button link :loading="avatarLoading" @click="loadAvatar">重新加载头像</el-button></el-alert>
    <div class="account-avatar-editor" v-loading="avatarLoading">
      <span class="account-avatar account-avatar--preview"><img v-if="previewAvatar" :src="previewAvatar" alt="头像预览" @error="previewFailed" /><template v-else>{{ username.slice(0, 1).toUpperCase() }}</template></span>
      <div class="account-avatar-editor-actions">
        <div><el-button :disabled="avatarSaving || avatarLoading" @click="fileInput?.click()">选择图片</el-button><el-button v-if="savedAvatar || draftFile" link :disabled="avatarSaving || avatarLoading" @click="resetAvatarDraft">恢复默认</el-button></div>
        <p>JPG、PNG 或 WebP，最大 2 MB，宽高不超过 4096 像素。图片居中裁切为正方形。</p>
        <small v-if="hasAvatarChange">预览未保存，点击“保存头像”后生效。</small>
      </div>
      <input ref="fileInput" type="file" accept="image/jpeg,image/png,image/webp" aria-label="选择头像图片" hidden :disabled="avatarSaving || avatarLoading" @change="chooseAvatar" />
    </div>
    <dl class="account-profile-fields"><div><dt>账号名称</dt><dd>{{ username }}</dd></div><div><dt>角色</dt><dd>{{ role }}</dd></div></dl>
    <template #footer><div class="account-profile-footer"><el-button :disabled="avatarSaving" @click="profileVisible = false">关闭</el-button><el-button :disabled="avatarSaving" @click="profileVisible = false; emit('settings')">账号设置</el-button><el-button type="primary" :disabled="!hasAvatarChange || avatarLoading" :loading="avatarSaving" @click="saveAvatar">保存头像</el-button></div></template>
  </el-dialog>
</template>

<style scoped>
:global(.sm-account-popover.el-popper) { border: 1px solid #e5eaf1; border-radius: .65rem; box-shadow: 0 8px 28px rgb(18 42 70 / 12%); overflow: hidden; }
.account-avatar-trigger { display: flex; align-items: center; gap: .65rem; min-width: 0; padding: .35rem .5rem; border: 1px solid transparent; border-radius: .5rem; background: transparent; color: #20344b; text-align: left; cursor: pointer; transition: background-color 160ms ease; }
.account-avatar-trigger:hover, .account-avatar-trigger.is-open { background: #f0f5fc; }
.account-avatar-trigger:focus-visible { outline: 2px solid #1677ff; outline-offset: 2px; }
.account-avatar { display: grid; place-items: center; flex-shrink: 0; width: 2.4rem; height: 2.4rem; border-radius: 50%; background: #0c559b; color: #fff; font-size: 1rem; font-weight: 600; }
.account-avatar img { display: block; width: 100%; height: 100%; border-radius: inherit; object-fit: cover; }
.account-avatar-editor { display: flex; align-items: center; gap: 1rem; padding: 1rem 0 1.5rem; }
.account-avatar-editor .account-avatar--preview { width: 5rem; height: 5rem; font-size: 2rem; overflow: hidden; }
.account-avatar-editor-actions { min-width: 0; flex: 1; }
.account-avatar-editor-actions > div { display: flex; flex-wrap: wrap; gap: .5rem; align-items: center; }
.account-avatar-editor-actions .el-button { margin: 0; }
.account-avatar-editor-actions p { margin: .6rem 0 0; color: #788da5; font-size: .75rem; line-height: 1.6; }
.account-avatar-editor-actions small { display: block; margin-top: .4rem; color: #1677ff; font-size: .7rem; }
.account-profile-footer { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: .5rem; }
.account-profile-footer .el-button { margin: 0; }
.account-trigger-identity { min-width: 0; max-width: 10rem; display: grid; gap: .25rem; }
.account-trigger-identity strong, .account-trigger-identity small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.account-trigger-identity strong { color: #18334f; font-size: .85rem; font-weight: 600; }
.account-trigger-identity small { color: #788da5; font-size: .7rem; }
.account-trigger-chevron { width: .9rem; height: .9rem; color: #8295aa; transform: rotate(90deg); }
.account-menu-panel { width: min(16rem, calc(100vw - 2rem)); padding: .5rem; color: #20344b; }
.account-menu-profile { display: flex; align-items: center; gap: .75rem; padding: .75rem .6rem 1rem; border-bottom: 1px solid #edf0f4; }
.account-avatar--large { width: 2.7rem; height: 2.7rem; font-size: 1.15rem; }
.account-menu-identity { min-width: 0; display: grid; gap: .4rem; }
.account-menu-identity strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: .9rem; }
.account-menu-identity > span { color: #7a8696; font-size: .75rem; }
.account-menu-actions { padding: .5rem 0 0; }
.account-menu-actions :deep(.el-dropdown-menu__item) { display: flex; align-items: center; gap: .7rem; min-height: 2.5rem; padding: 0 .75rem; border-radius: .4rem; font-size: .8rem; }
.account-menu-actions .app-icon { width: 1.1rem; height: 1.1rem; color: #8091a5; }
.account-profile-fields { margin: 0; }
.account-profile-fields > div { display: grid; grid-template-columns: 5rem minmax(0, 1fr); gap: 1rem; padding: .8rem 0; border-bottom: 1px solid #edf0f4; }
.account-profile-fields dt { color: #788da5; }
.account-profile-fields dd { margin: 0; color: #18334f; overflow-wrap: anywhere; }
@media (max-width: 1100px) { .account-trigger-identity, .account-trigger-chevron { display: none; } }
@media (max-width: 760px) { .account-avatar-trigger { padding: .2rem; } .account-avatar { width: 2rem; height: 2rem; font-size: .85rem; } }
@media (prefers-reduced-motion: reduce) { .account-avatar-trigger { transition: none; } }
</style>
