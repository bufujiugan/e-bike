<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
const emit = defineEmits<{created: []}>()
const visible = ref(false), saving = ref(false), error = ref(''), customers = ref<Array<{id:number;name:string}>>([]), mode = ref('new')
const form = reactive({ username:'',password:'',displayName:'',companyName:'',customerId:undefined as number | undefined })
async function open() {
  Object.assign(form,{username:'',password:'',displayName:'',companyName:'',customerId:undefined}); mode.value='new'; error.value=''; visible.value=true
  try { customers.value=(await api.get('/api/master/customers')).data.data.filter((c: {status:string})=>c.status==='ENABLED') }
  catch { error.value='已有客户选项加载失败，可关闭后重试，或新建客户公司。' }
}
async function save() {
  if(saving.value)return
  if(!form.username || !form.password || !form.displayName || (mode.value==='new'?!form.companyName:!form.customerId)){error.value='请填写完整的账号与公司信息';return}
  saving.value=true;error.value=''
  try {
    await api.post('/api/customer-accounts',{username:form.username,password:form.password,displayName:form.displayName,customerId:mode.value==='existing'?form.customerId:null,companyName:mode.value==='new'?form.companyName:null})
    form.password='';visible.value=false;ElMessage.success('客户账号已创建，使用同一登录入口，首次登录需改密');emit('created')
  } catch(cause){error.value=api.isAxiosError(cause)?cause.response?.data?.message??'创建失败，请重试':'创建失败，请重试'}
  finally{saving.value=false}
}
</script>
<template>
  <el-button type="primary" @click="open">新增客户登录账号</el-button>
  <el-dialog v-model="visible" title="新增客户登录账号" width="min(540px,94vw)" :close-on-click-modal="false" :before-close="(done: () => void) => { if (!saving) { form.password=''; done() } }">
    <el-alert title="客户与内部员工使用同一登录入口；该账号只能访问所属公司的客户工作台。" type="info" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form label-position="top" @submit.prevent="save" :disabled="saving">
      <el-form-item label="客户公司"><el-radio-group v-model="mode"><el-radio value="new">新建客户公司</el-radio><el-radio value="existing">已有客户公司</el-radio></el-radio-group></el-form-item>
      <el-form-item v-if="mode==='new'" label="公司名称" required><el-input v-model="form.companyName" maxlength="150" placeholder="例如：演示客户公司" /></el-form-item>
      <el-form-item v-else label="所属公司" required><el-select v-model="form.customerId" filterable><el-option v-for="c in customers" :key="c.id" :value="c.id" :label="c.name" /></el-select></el-form-item>
      <el-form-item label="登录账号" required><el-input v-model="form.username" maxlength="64" placeholder="3–64位字母、数字、点、下划线或连字符" autocomplete="off" /></el-form-item>
      <el-form-item label="联系人姓名" required><el-input v-model="form.displayName" maxlength="100" /></el-form-item>
      <el-form-item label="初始密码" required><el-input v-model="form.password" type="password" show-password autocomplete="new-password" maxlength="72" placeholder="8–72位，首次登录后修改" /></el-form-item>
      <el-button :disabled="saving" @click="visible=false;form.password=''">取消</el-button><el-button type="primary" native-type="submit" :loading="saving">创建客户账号</el-button>
    </el-form>
  </el-dialog>
</template>

