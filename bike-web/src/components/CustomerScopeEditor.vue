<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
const props = defineProps<{ username: string; id?: number }>()
const visible = ref(false), busy = ref(false), error = ref(''), reason = ref('')
const userId = ref(0), revision = ref(0), selected = ref<number[]>([])
const customers = ref<{ id: number; code: string; name: string; status: string }[]>([])
const assigned = ref<typeof customers.value>([]), customerSearch = ref(''), customerPage = ref(1), customerTotal = ref(0), searching = ref(false)
const options = computed(() => [...new Map([...assigned.value, ...customers.value].map(c => [c.id, c])).values()])
function message(e: unknown) { return api.isAxiosError(e) ? e.response?.data?.message || '客户授权操作失败' : e instanceof Error ? e.message : '客户授权操作失败' }
async function searchCustomers(page = 1) {
  searching.value = true
  try {
    assigned.value = options.value.filter(c => selected.value.includes(c.id))
    const result = (await api.get('/api/customer-scopes/customers', { params: { keyword: customerSearch.value.trim() || undefined, page, pageSize: 50 } })).data.data
    customers.value = result.records; customerPage.value = result.page; customerTotal.value = result.total
  } catch (e) { error.value = message(e) }
  finally { searching.value = false }
}
async function load() {
  busy.value = true; error.value = ''
  try {
    let id = props.id
    if (!id) {
      const users = (await api.get('/api/customer-scopes/users', { params: { keyword: props.username, pageSize: 100 } })).data.data.records
      id = users.find((item: { username: string }) => item.username === props.username)?.id
    }
    if (!id) throw new Error('未找到内部 Web 账号')
    userId.value = id
    const scope = (await api.get(`/api/users/${id}/customer-scope`)).data.data
    revision.value = scope.revision; selected.value = [...scope.customerIds]; assigned.value = scope.customers
    customers.value = []; customerSearch.value = ''; reason.value = ''
    await searchCustomers()
  } catch (e) { error.value = message(e); userId.value = 0 }
  finally { busy.value = false }
}
async function open() { visible.value = true; await load() }
async function save() {
  if (busy.value || !userId.value) return
  if (!reason.value.trim()) { error.value = '请填写授权或撤权原因'; return }
  busy.value = true; error.value = ''
  try {
    const result = (await api.put(`/api/users/${userId.value}/customer-scope`, { revision: revision.value, customerIds: selected.value, reason: reason.value.trim() })).data.data
    revision.value = result.revision; selected.value = [...result.customerIds]; assigned.value = result.customers; reason.value = ''
    ElMessage.success('客户授权已保存，下次请求立即生效')
  } catch (e) { error.value = message(e) }
  finally { busy.value = false }
}
</script>
<template>
  <button class="row-action" @click="open">客户授权</button>
  <el-dialog v-model="visible" :title="`${username} · 客户授权范围`" width="min(700px, 95vw)" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy">
    <el-form label-position="top" @submit.prevent="save">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <p>业务权限与客户授权同时生效。清空名单会撤销此账号的逐客户授权；拥有“全部客户范围”角色或超级管理员角色的账号仍可访问全部客户。</p>
      <el-form-item label="搜索待授权客户">
        <el-input v-model="customerSearch" placeholder="客户编号或名称" :disabled="busy || searching" @keydown.enter.prevent="searchCustomers()" />
        <el-button :disabled="busy || searching" @click="searchCustomers()">查询</el-button>
        <el-button :disabled="busy || searching || customerPage <= 1" @click="searchCustomers(customerPage - 1)">上一页</el-button>
        <el-button :disabled="busy || searching || customerPage * 50 >= customerTotal" @click="searchCustomers(customerPage + 1)">下一页</el-button>
        <span>匹配 {{ customerTotal }} 个客户，第 {{ customerPage }} 页；已选客户跨页保留。</span>
      </el-form-item>
      <el-form-item label="授权客户">
        <el-select v-model="selected" multiple filterable :disabled="busy || searching || !userId" style="width:100%">
          <el-option v-for="customer in options" :key="customer.id" :value="customer.id" :label="`${customer.code} · ${customer.name}${customer.status === 'ENABLED' ? '' : '（停用）'}`" />
        </el-select>
      </el-form-item>
      <el-form-item label="修改原因" required><el-input v-model="reason" maxlength="500" :disabled="busy" /></el-form-item>
      <el-button type="primary" native-type="submit" :loading="busy" :disabled="!userId || searching">保存授权</el-button>
      <el-button :disabled="busy" @click="load">重新读取</el-button>
      <p>授权版本 {{ revision }}；出现版本冲突时请重新读取，再核对修改。</p>
    </el-form>
  </el-dialog>
</template>
