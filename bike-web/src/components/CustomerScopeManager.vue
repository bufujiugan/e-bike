<script setup lang="ts">
import { onMounted, ref } from 'vue'
import api from '../api'
import CustomerScopeEditor from './CustomerScopeEditor.vue'
type User = { id: number; username: string; displayName: string; userType: string; enabled: boolean }
const users = ref<User[]>([]), keyword = ref(''), busy = ref(false), error = ref(''), page = ref(1), total = ref(0)
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
    <h2>内部客户授权</h2>
    <p>选择内部账号，分配或撤销其可访问的客户。业务操作仍需对应的功能权限。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form inline @submit.prevent="load()"><el-form-item label="账号或姓名"><el-input v-model="keyword" :disabled="busy" /></el-form-item><el-button native-type="submit" :loading="busy">查询</el-button></el-form>
    <el-table :data="users" v-loading="busy" empty-text="没有匹配的内部账号">
      <el-table-column prop="username" label="账号" /><el-table-column prop="displayName" label="姓名" /><el-table-column prop="userType" label="账号类型" />
      <el-table-column label="状态"><template #default="{ row }">{{ row.enabled ? '启用' : '停用' }}</template></el-table-column>
      <el-table-column label="操作"><template #default="{ row }"><CustomerScopeEditor :id="row.id" :username="row.username" /></template></el-table-column>
    </el-table>
    <p>共 {{ total }} 个账号，第 {{ page }} 页</p>
    <el-button :disabled="busy || page <= 1" @click="load(page - 1)">上一页</el-button><el-button :disabled="busy || page * 20 >= total" @click="load(page + 1)">下一页</el-button>
  </section>
</template>
