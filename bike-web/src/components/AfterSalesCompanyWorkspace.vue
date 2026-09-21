<script setup lang="ts">
import { computed, onMounted, onUnmounted, watch, ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import AfterSalesContactFields from './AfterSalesContactFields.vue'
import { afterSalesCompanies, blankContact, type AfterSalesCompany, type StaffOption, type CompanyMember } from '../after-sales-company-api'
import { afterSalesError, type CaseHistory } from '../after-sales-api'
const props=defineProps<{authContext:{username:string;userType:string;agentId:number|null;roles:string[];permissions:string[]}|null}>()
const external=computed(()=>props.authContext?.userType==='AGENT'||props.authContext?.roles.includes('WEB_AGENT'))
const canManage=computed(()=>!external.value&&props.authContext?.userType!=='PDA_OPERATOR'&&!props.authContext?.roles.includes('PDA_OPERATOR')&&Boolean(props.authContext?.roles.includes('SUPER_ADMIN')||props.authContext?.permissions.includes('AFTER_SALES_COMPANY_MANAGE')))
const rows=ref<AfterSalesCompany[]>([]),selected=ref<AfterSalesCompany|null>(null),loading=ref(false),saving=ref(false),error=ref(''),visible=ref(false)
const form=reactive(blankContact()),salesOptions=ref<StaffOption[]>([]),salesIds=ref<number[]>([]),members=ref<CompanyMember[]>([])
const history=ref<CaseHistory[]>([])
const historyNames:Record<string,string>={COMPANY_CONTACT_UPDATED:'更新公司联系资料',COMPANY_SALES_ASSIGNED:'调整负责销售'}
let version=0,listVersion=0
async function load(){const current=++listVersion;loading.value=true;error.value='';try{const data=await afterSalesCompanies.list();if(current===listVersion)rows.value=data}catch(e){if(current===listVersion)error.value=afterSalesError(e)}finally{if(current===listVersion)loading.value=false}}
async function open(company:AfterSalesCompany){const current=++version;selected.value=null;visible.value=true;loading.value=true;try{
  const data=await afterSalesCompanies.get(company.agentId);if(current!==version)return
  selected.value=data;Object.assign(form,blankContact());for(const key of Object.keys(form) as (keyof typeof form)[])form[key]=data[key]||''
  if(canManage.value){const [options,sales,employees,events]=await Promise.all([afterSalesCompanies.staff(),afterSalesCompanies.salespeople(company.agentId),afterSalesCompanies.members(company.agentId),afterSalesCompanies.history(company.agentId)]);if(current!==version)return;salesOptions.value=options.salesUsers;salesIds.value=sales.map(item=>item.id);members.value=employees;history.value=events}
}catch(e){if(current===version){ElMessage.error(afterSalesError(e));visible.value=false}}finally{if(current===version)loading.value=false}}
async function save(){if(!selected.value)return;if(form.contactEmail&&!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(form.contactEmail)){ElMessage.warning('请填写有效Email');return}const current=version;saving.value=true;try{const updated=await afterSalesCompanies.save(selected.value.agentId,{...form,countryCode:form.countryCode.toUpperCase()});if(current!==version)return;selected.value=updated;const events=await afterSalesCompanies.history(updated.agentId);if(current!==version)return;history.value=events;await load();ElMessage.success('公司联系资料已保存，历史案例资料保持原样')}catch(e){if(current===version)ElMessage.error(afterSalesError(e))}finally{if(current===version)saving.value=false}}
async function saveSales(){if(!selected.value)return;const current=version,agentId=selected.value.agentId;saving.value=true;try{await afterSalesCompanies.setSalespeople(agentId,salesIds.value);if(current!==version)return;const events=await afterSalesCompanies.history(agentId);if(current!==version)return;history.value=events;ElMessage.success('销售负责范围已更新')}catch(e){if(current===version)ElMessage.error(afterSalesError(e))}finally{if(current===version)saving.value=false}}
onMounted(load)
onUnmounted(()=>{version++;listVersion++})
watch(visible,value=>{if(!value){version++;saving.value=false;selected.value=null;history.value=[];members.value=[];salesOptions.value=[];salesIds.value=[]}})
watch(()=>props.authContext?.username,()=>{version++;listVersion++;visible.value=false;rows.value=[];void load()})
</script>
<template>
  <section aria-label="售后公司资料"><p>公司联系资料用于创建新案例时自动带出。修改公司资料不会改动历史案例中的联系人和地址。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false"/><el-button :loading="loading" @click="load">刷新公司资料</el-button>
    <el-table :data="rows" v-loading="loading" empty-text="当前范围内没有可查看的公司"><el-table-column prop="companyName" label="经销商公司" min-width="160"/><el-table-column prop="contactName" label="联系人" min-width="110"/><el-table-column prop="contactPhone" label="联系电话" min-width="130"/><el-table-column prop="countryCode" label="国家" width="75"/><el-table-column label="操作" width="120"><template #default="scope"><el-button link type="primary" @click="open(scope.row)">{{canManage?'维护公司资料':'查看资料'}}</el-button></template></el-table-column></el-table>
    <el-dialog v-model="visible" :title="selected?.companyName||'公司资料'" width="min(800px,95vw)" append-to-body destroy-on-close :close-on-click-modal="false">
      <div v-loading="loading"><template v-if="selected"><el-form label-position="top"><AfterSalesContactFields :model="form" :disabled="!canManage||saving"/><el-button v-if="canManage" type="primary" :loading="saving" @click="save">保存联系资料</el-button></el-form>
      <template v-if="canManage"><h3>负责销售</h3><p>只读销售仅能查看已分配公司；移除分配后，下一次访问即按新范围检查。</p><el-select v-model="salesIds" multiple filterable placeholder="选择内部销售人员" :disabled="saving" style="width:100%"><el-option v-for="item in salesOptions" :key="item.id" :value="item.id" :label="item.name"/></el-select><el-button :loading="saving" @click="saveSales">保存销售范围</el-button><h3>公司员工账号</h3><el-table :data="members" empty-text="暂无公司员工账号"><el-table-column prop="displayName" label="姓名"/><el-table-column prop="username" label="账号"/><el-table-column label="状态"><template #default="scope">{{scope.row.enabled?'启用':'停用'}}</template></el-table-column></el-table><p>员工账号的创建和停用由管理员在用户管理中操作，绑定同一公司后共享该公司的售后范围。</p><h3>公司变更记录</h3><p>显示最近200条公司联系资料和销售范围变更。</p><el-table :data="history" empty-text="暂无变更"><el-table-column label="操作"><template #default="scope">{{historyNames[scope.row.action]||scope.row.action}}</template></el-table-column><el-table-column prop="operatorName" label="操作人"/><el-table-column label="时间" min-width="170"><template #default="scope">{{scope.row.operationTime?.replace('T',' ').slice(0,19)}}</template></el-table-column></el-table></template></template></div>
    </el-dialog>
  </section>
</template>
<style scoped>p{color:#667788;line-height:1.7;font-size:13px}h3{margin-top:26px}.el-table{margin-top:14px}.el-select+.el-button{margin-top:12px}</style>
