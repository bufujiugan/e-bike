<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { customerProfileApi, type CustomerProfile } from '../customer-profile-api'
defineProps<{ canWrite: boolean }>()
const visible = ref(false), busy = ref(false), error = ref(''), customerId = ref<number>(), selected = ref<CustomerProfile | null>(null)
const options = ref<{ id: number; code: string; name: string }[]>([])
const fields = [['address', '地址', 1000], ['country', '国家 / 地区', 100], ['email', 'Email', 254], ['preferredCurrency', '币种偏好（CNY / USD / EUR）', 3], ['businessOwner', '业务归属', 150]] as const
const draft = reactive({ address: '', country: '', email: '', preferredCurrency: '', businessOwner: '', revision: 0, reason: '' })
let loadVersion = 0
async function open() {
  visible.value = true; error.value = ''; busy.value = true
  try { options.value = (await api.get('/api/master/customers')).data.data }
  catch (e) { error.value = message(e) }
  finally { busy.value = false }
}
function message(e: unknown) { return api.isAxiosError(e) ? e.response?.data?.message || '客户资料操作失败' : '客户资料操作失败' }
async function load() {
  const version = ++loadVersion, id = customerId.value; selected.value = null
  if (!id) return
  busy.value = true; error.value = ''
  try {
    const result = await customerProfileApi.get(id)
    if (version !== loadVersion) return
    selected.value = result
    for (const [key] of fields) draft[key] = result[key] || ''
    draft.revision = result.revision; draft.reason = ''
  } catch (e) { if (version === loadVersion) error.value = message(e) }
  finally { if (version === loadVersion) busy.value = false }
}
async function save() {
  const id = selected.value?.customerId
  if (!id || busy.value) return
  if (!draft.reason.trim()) { error.value = '请填写修改原因'; return }
  busy.value = true; error.value = ''
  try { await customerProfileApi.update(id, { ...draft }); await load(); ElMessage.success('客户商务资料已保存，历史 PI 保持原快照') }
  catch (e) { error.value = message(e) }
  finally { busy.value = false }
}
</script>
<template>
  <el-button @click="open">客户商务资料</el-button>
  <el-dialog v-model="visible" title="客户商务资料" width="min(700px, 95vw)" :close-on-click-modal="false">
    <el-form v-loading="busy" label-position="top" @submit.prevent="save">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-form-item label="客户"><el-select v-model="customerId" filterable :disabled="busy" @change="load"><el-option v-for="item in options" :key="item.id" :value="item.id" :label="`${item.code} · ${item.name}`" /></el-select><el-button :disabled="!customerId || busy" @click="load">重新读取</el-button></el-form-item>
      <template v-if="selected">
        <p>{{ selected.contactName || '未记录联系人' }} · {{ selected.contactPhone || '未记录电话' }} · {{ selected.status === 'ENABLED' ? '启用' : '停用' }} · 资料版本 {{ selected.revision }}</p>
        <el-form-item v-for="[key, label, max] in fields" :key="key" :label="label"><el-input v-model="draft[key]" :maxlength="max" :type="key === 'address' ? 'textarea' : 'text'" :readonly="!canWrite" /></el-form-item>
        <p>新建 PI 时带入这些资料；历史单据保留当时快照。业务归属填写部门或负责人，不改变账号访问权限。</p>
        <template v-if="canWrite"><el-form-item label="修改原因" required><el-input v-model="draft.reason" maxlength="500" /></el-form-item><el-button type="primary" native-type="submit" :loading="busy">保存商务资料</el-button></template>
      </template>
    </el-form>
  </el-dialog>
</template>
