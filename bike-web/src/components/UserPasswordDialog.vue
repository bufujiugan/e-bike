<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, type InputInstance } from 'element-plus'
import api from '../api'
import { tr } from '../i18n/language'

const props = defineProps<{ userId: string; username: string }>()
const emit = defineEmits<{ closed: []; assigned: [requiresSignIn: boolean] }>()
const visible = ref(true)
const saving = ref(false)
const newPassword = ref('')
const confirmation = ref('')
const passwordInput = ref<InputInstance>()
const confirmationInput = ref<InputInstance>()
const passwordTouched = ref(false)
const confirmationTouched = ref(false)
const requestError = ref('')
const passwordHint = '新密码至少 8 个字符，最多 72 字节'
const passwordError = computed(() => {
  if (!newPassword.value.trim()) return '新密码不能为空'
  if (newPassword.value.length < 8 || new TextEncoder().encode(newPassword.value).length > 72) return passwordHint
  return ''
})
const confirmationError = computed(() => confirmation.value === newPassword.value ? '' : '两次输入的新密码不一致')

async function savePassword() {
  if (saving.value) return
  passwordTouched.value = true
  confirmationTouched.value = true
  requestError.value = ''
  if (passwordError.value) {
    passwordInput.value?.focus()
    return
  }
  if (confirmationError.value) {
    confirmationInput.value?.focus()
    return
  }

  saving.value = true
  try {
    const { data } = await api.put<{ data: { requiresSignIn: boolean } }>(
      `/api/users/${props.userId}/password`, { newPassword: newPassword.value },
    )
    newPassword.value = ''
    confirmation.value = ''
    visible.value = false
    ElMessage.success(tr(data.data.requiresSignIn ? '密码已修改，请使用新密码重新登录' : '密码已修改'))
    emit('assigned', data.data.requiresSignIn)
  } catch (error) {
    requestError.value = api.isAxiosError(error) ? error.response?.data?.message ?? '密码修改失败' : '密码修改失败'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title='tr("修改密码")' width="min(460px, calc(100vw - 32px))"
    :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving"
    @opened="passwordInput?.focus()" @closed="emit('closed')">
    <p class="password-account">{{ tr('登录账号') }} <strong>{{ username }}</strong></p>
    <p>{{ tr('保存后，新密码立即生效，已有会话将退出。') }}</p>
    <el-alert v-if="requestError" :title="tr(requestError)" type="error" :closable="false" />
    <el-form label-position="top" :disabled="saving" @submit.prevent="savePassword">
      <el-form-item :label='tr("新密码")' :error="passwordTouched ? tr(passwordError) : ''" required>
        <el-input ref="passwordInput" v-model="newPassword" type="password" show-password maxlength="72"
          autocomplete="new-password" aria-describedby="assigned-password-hint" @blur="passwordTouched = true" />
        <span id="assigned-password-hint" class="password-hint">{{ tr(passwordHint) }}</span>
      </el-form-item>
      <el-form-item :label='tr("确认新密码")' :error="confirmationTouched ? tr(confirmationError) : ''" required>
        <el-input ref="confirmationInput" v-model="confirmation" type="password" show-password maxlength="72"
          autocomplete="new-password" @blur="confirmationTouched = true" />
      </el-form-item>
      <div class="password-actions">
        <el-button :disabled="saving" @click="visible = false">{{ tr('取消') }}</el-button>
        <el-button type="primary" native-type="submit" :loading="saving">{{ tr('保存密码') }}</el-button>
      </div>
    </el-form>
  </el-dialog>
</template>

<style scoped>
.password-account { margin-top: 0; overflow-wrap: anywhere; }
.password-account strong { margin-inline-start: 8px; }
.password-hint { color: var(--el-text-color-secondary); line-height: 1.5; }
.password-actions { display: flex; justify-content: flex-end; }
</style>
