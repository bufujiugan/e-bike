<script setup lang="ts">
import { ref } from 'vue'

defineProps<{ username: string; role: string; busy: boolean }>()
const emit = defineEmits<{ submit: [] }>()
const visible = defineModel<boolean>({ default: false })
const currentPassword = defineModel<string>('currentPassword', { default: '' })
const newPassword = defineModel<string>('newPassword', { default: '' })
const confirmPassword = defineModel<string>('confirmPassword', { default: '' })
const isEditingPassword = ref(false)

function resetPasswordFields() {
  currentPassword.value = ''
  newPassword.value = ''
  confirmPassword.value = ''
  isEditingPassword.value = false
}
</script>

<template>
  <el-dialog v-model="visible" title="账号设置" width="min(460px, calc(100vw - 32px))" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" class="account-settings-dialog" @closed="resetPasswordFields">
    <dl class="account-settings-profile"><div><dt>账号</dt><dd>{{ username }}</dd></div><div><dt>角色</dt><dd>{{ role }}</dd></div></dl>
    <div class="account-settings-security"><div><strong>登录密码</strong><p>修改密码后需重新登录。</p></div><el-button v-if="!isEditingPassword" @click="isEditingPassword = true">修改密码</el-button></div>
    <el-form v-if="isEditingPassword" label-position="top" :disabled="busy" @submit.prevent="emit('submit')">
      <el-form-item label="当前密码"><el-input v-model="currentPassword" aria-label="当前密码" type="password" show-password autocomplete="current-password" maxlength="100" /></el-form-item>
      <el-form-item label="新密码"><el-input v-model="newPassword" aria-label="新密码" type="password" show-password autocomplete="new-password" placeholder="8–100 个字符" maxlength="100" /></el-form-item>
      <el-form-item label="确认新密码"><el-input v-model="confirmPassword" aria-label="确认新密码" type="password" show-password autocomplete="new-password" maxlength="100" /></el-form-item>
      <div class="account-settings-actions"><el-button :disabled="busy" @click="resetPasswordFields">取消修改</el-button><el-button type="primary" native-type="submit" :loading="busy">保存密码</el-button></div>
    </el-form>
    <template #footer><el-button :disabled="busy" @click="visible = false">关闭</el-button></template>
  </el-dialog>
</template>

<style scoped>
.account-settings-profile { margin: 0; padding: 0 0 18px; border-bottom: 1px solid #e8eef5; }
.account-settings-profile > div { display: flex; gap: 20px; padding: 7px 0; font-size: 13px; }
.account-settings-profile dt { flex: 0 0 48px; color: #7a8696; }
.account-settings-profile dd { margin: 0; overflow-wrap: anywhere; color: #20344b; }
.account-settings-security { display: flex; justify-content: space-between; align-items: center; gap: 14px; padding: 20px 0; }
.account-settings-security strong { font-size: 14px; color: #20344b; }
.account-settings-security p { margin: 6px 0 0; color: #7a8696; font-size: 12px; }
.account-settings-actions { display: flex; justify-content: flex-end; gap: 8px; padding: 4px 0; }
.account-settings-actions .el-button + .el-button { margin-left: 0; }
</style>
