<script setup lang="ts">
import { tr, trInline } from '../i18n/language'
import RecordIdColumn from './table/RecordIdColumn.vue'
import { onMounted, ref } from 'vue'
import api from '../api'
import CustomerScopeEditor from './CustomerScopeEditor.vue'
type User = { id: number; username: string; displayName: string; userType: string; enabled: boolean; createdAt: string; updatedAt: string }
const users = ref<User[]>([]), keyword = ref(''), busy = ref(false), error = ref(''), page = ref(1), total = ref(0)
const formatDateTime = (value?: string | null) => value ? value.replace('T', ' ').slice(0, 19) : '—'
async function load(next = 1) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    const result = (await api.get('/api/customer-scopes/users', { params: { keyword: keyword.value.trim() || undefined, page: next, pageSize: 20 } })).data.data
    users.value = result.records; total.value = result.total; page.value = result.page
  } catch (e) { users.value = []; total.value = 0; error.value = api.isAxiosError(e) ? e.response?.data?.message || '账号读取失败' : '账号读取失败' }
  finally { busy.value = false }
}
onMounted(() => load())
</script>
<template>
  <section class="panel data-panel">
    <h2>{{ tr("内部客户授权") }}</h2>
    <p>{{ tr("选择内部账号，分配或撤销其可访问的客户。业务操作仍需对应的功能权限。") }}</p>
    <el-alert v-if="error" :title="tr(error)" type="error" :closable="false" />
    <el-form inline @submit.prevent="load()"><el-form-item :label='tr("账号或姓名")'><el-input v-model="keyword" :disabled="busy" /></el-form-item><el-button native-type="submit" :loading="busy">{{ tr("查询") }}</el-button></el-form>
    <el-table :data="users" v-loading="busy" :empty-text='tr("没有匹配的内部账号")'>
      <RecordIdColumn />
      <el-table-column prop="username" :label='tr("账号")' /><el-table-column prop="displayName" :label='tr("姓名")' /><el-table-column prop="userType" :label='tr("账号类型")' />
      <el-table-column :label='tr("状态")'><template #default="{ row }">{{ row.enabled ? tr('启用') : tr('停用') }}</template></el-table-column>
      <el-table-column :label='tr("创建时间")' min-width="175"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
      <el-table-column :label='tr("修改时间")' min-width="175"><template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template></el-table-column>
      <el-table-column :label='tr("操作")'><template #default="{ row }"><CustomerScopeEditor :id="row.id" :username="row.username" /></template></el-table-column>
    </el-table>
    <p>{{ trInline("共") }} {{ total }} {{ trInline("个账号，第") }} {{ page }} {{ trInline("页") }}</p>
    <el-button :disabled="busy || page <= 1" @click="load(page - 1)">{{ tr("上一页") }}</el-button><el-button :disabled="busy || page * 20 >= total" @click="load(page + 1)">{{ tr("下一页") }}</el-button>
  </section>
</template>
