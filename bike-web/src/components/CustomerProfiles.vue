<script setup lang="ts">
import { tr, trInline } from '../i18n/language'
import { computed, ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { customerProfileApi, type CustomerProfile } from '../customer-profile-api'
const props = defineProps<{ canWrite: boolean }>()
const visible = ref(false), busy = ref(false), error = ref(''), customerId = ref<number>(), selected = ref<CustomerProfile | null>(null)
type CustomerOption = { id: number; code: string; name: string }
const options = ref<CustomerOption[]>([])
const rowCustomer = ref<{ code: string; name: string }>()
const saving = ref(false)
const fields = [['address', '地址', 1000], ['country', '国家 / 地区', 100], ['email', 'Email', 254], ['preferredCurrency', '币种偏好（CNY / USD / EUR）', 3], ['businessOwner', '业务归属', 150]] as const
const draft = reactive({ address: '', country: '', email: '', preferredCurrency: '', businessOwner: '', revision: 0, reason: '' })
const hasUnsavedChanges = computed(() => {
  const profile = selected.value
  return props.canWrite && !!profile && (draft.reason !== '' || fields.some(([key]) => draft[key] !== (profile[key] ?? '')))
})
let confirmingDiscard = false
let loadVersion = 0
async function open() {
  if (rowCustomer.value) {
    rowCustomer.value = undefined
    selected.value = null
  }
  const version = ++loadVersion
  visible.value = true; error.value = ''; busy.value = true
  try {
    const response = await api.get<{ data: CustomerOption[] }>('/api/master/customers')
    if (version === loadVersion) options.value = response.data.data
  } catch (e) { if (version === loadVersion) error.value = message(e) }
  finally { if (version === loadVersion) busy.value = false }
}
function openForCustomer(code: string, name: string) {
  if (visible.value || saving.value) return
  customerId.value = undefined
  rowCustomer.value = { code, name }
  visible.value = true
  void load()
}
defineExpose({ openForCustomer })

function message(e: unknown) { return api.isAxiosError(e) ? e.response?.data?.message || '客户资料操作失败' : '客户资料操作失败' }
function applyProfile(profile: CustomerProfile) {
  selected.value = profile
  for (const [key] of fields) draft[key] = profile[key] ?? ''
  draft.revision = profile.revision
  draft.reason = ''
}

async function load(preserveCurrent = false) {
  const version = ++loadVersion
  const customer = rowCustomer.value
  let id = customerId.value
  if (!preserveCurrent) selected.value = null
  if (!customer && !id) return
  busy.value = true; error.value = ''
  try {
    if (customer) {
      const response = await api.get<{ data: CustomerOption[] }>('/api/master/customers', { params: { keyword: customer.code } })
      if (version !== loadVersion) return
      id = response.data.data.find(item => item.code === customer.code)?.id
    }
    if (!id) {
      error.value = '客户不存在'
      return
    }
    const result = await customerProfileApi.get(id)
    if (version !== loadVersion) return
    applyProfile(result)
  } catch (e) { if (version === loadVersion) error.value = message(e) }
  finally { if (version === loadVersion) busy.value = false }
}
async function save() {
  const id = selected.value?.customerId
  if (!id || busy.value) return
  if (!draft.reason.trim()) { error.value = '请填写修改原因'; return }
  busy.value = true; saving.value = true; error.value = ''
  try {
    const savedProfile = await customerProfileApi.update(id, { ...draft })
    applyProfile(savedProfile)
    if (rowCustomer.value) visible.value = false
    ElMessage.success(tr('客户商务资料已保存，历史 PI 保持原快照'))
  }
  catch (e) { error.value = message(e) }
  finally { busy.value = false; saving.value = false }
}
async function confirmDiscard(messageText: string, confirmButtonText: string) {
  if (confirmingDiscard || saving.value) return false
  if (!hasUnsavedChanges.value) return true
  confirmingDiscard = true
  try {
    await ElMessageBox.confirm(tr(messageText), tr('有未保存的修改'), {
      type: 'warning',
      confirmButtonType: 'danger',
      confirmButtonText: tr(confirmButtonText),
      cancelButtonText: tr('继续编辑'),
      autofocus: false,
      closeOnClickModal: false,
    })
    return true
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(tr(message(e)))
    return false
  } finally {
    confirmingDiscard = false
  }
}

async function reload() {
  if (await confirmDiscard('重新读取会覆盖这次修改。', '放弃修改并重新读取')) await load(true)
}

async function close(done: () => void) {
  if (!await confirmDiscard('关闭后，这次修改将丢失。', '放弃修改并关闭')) return
  if (hasUnsavedChanges.value && selected.value) applyProfile(selected.value)
  done()
}
</script>
<template>
  <el-button @click="open">{{ tr("客户商务资料") }}</el-button>
  <el-dialog v-model="visible" :title="tr(rowCustomer ? 'PI 商务资料' : '客户商务资料')" width="min(700px, 95vw)" :close-on-click-modal="false" :before-close="close">
    <el-form v-loading="busy" label-position="top" @submit.prevent="save">
      <el-alert v-if="error" :title="tr(error)" type="error" :closable="false" />
      <el-form-item :label='tr("客户")'>
        <strong v-if="rowCustomer">{{ selected?.code ?? rowCustomer.code }} · {{ selected?.name ?? rowCustomer.name }}</strong>
        <el-select v-else v-model="customerId" filterable :disabled="busy" @change="load()"><el-option v-for="item in options" :key="item.id" :value="item.id" :label="`${item.code} · ${item.name}`" /></el-select>
        <el-button :disabled="!(rowCustomer || customerId) || busy" @click="reload">{{ tr("重新读取") }}</el-button>
      </el-form-item>
      <template v-if="selected">
        <p>{{ selected.contactName || tr('未记录联系人') }} · {{ selected.contactPhone || tr('未记录电话') }} · {{ selected.status === 'ENABLED' ? tr('启用') : tr('停用') }} {{ trInline("· 资料版本") }} {{ selected.revision }}</p>
        <el-form-item v-for="[key, label, max] in fields" :key="key" :label="tr(label)"><el-input v-model="draft[key]" :maxlength="max" :type="key === 'address' ? 'textarea' : 'text'" :readonly="!canWrite" /></el-form-item>
        <p>{{ tr("新建 PI 时带入这些资料；历史单据保留当时快照。业务归属填写部门或负责人，不改变账号访问权限。") }}</p>
        <template v-if="canWrite"><el-form-item :label='tr("修改原因")' required><el-input v-model="draft.reason" maxlength="500" /></el-form-item><el-button type="primary" native-type="submit" :loading="busy">{{ tr("保存商务资料") }}</el-button></template>
      </template>
    </el-form>
  </el-dialog>
</template>
