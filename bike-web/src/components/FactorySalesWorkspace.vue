<script setup lang="ts">
import { tr, trInline } from '../i18n/language'
import { computed, defineAsyncComponent, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { createUuid } from '../utils/uuid'
import { piApi, type PiOptions } from '../pi-api'
import { hasAnyPermission, hasPermission, type PermissionContext } from '../permissions'
import { europeSalesApi, factorySteps, type FactorySalesDraft, type FactorySalesOrder, type FactoryStep } from '../europe-sales-api'
import SalesSettlementPanel from './SalesSettlementPanel.vue'
const OrderJourney = defineAsyncComponent(() => import('./OrderJourney.vue'))
const journeyOrderNo = ref('')

const props = defineProps<{ context: PermissionContext | null; businessType: 'FACTORY_DOMESTIC' | 'FACTORY_EXPORT' }>()
const rows = ref<FactorySalesOrder[]>([]), total = ref(0), page = ref(1), keyword = ref('')
const busy = ref(false), saving = ref(false), error = ref(''), formError = ref(''), visible = ref(false)
const detail = ref<FactorySalesOrder | null>(null), selectedStep = ref<FactoryStep>('CONTRACT')
const options = ref<PiOptions>({ customers: [], agents: [], skus: [], orders: [] })
const optionsBusy = ref(false)
const canWrite = computed(() => hasPermission(props.context, 'PI_WRITE'))
const canFinance = computed(() => hasAnyPermission(props.context, ['PI_PAYMENT_CONFIRM','PI_RELEASE','PI_PAYMENT_REVERSE']))
const canOrders = computed(() => hasPermission(props.context,'ORDER_READ'))
const localSale = computed(() => props.businessType === 'FACTORY_DOMESTIC')
const stepNames = Object.fromEntries(factorySteps.map(step=>[step.code,step.name]))
const blank = (): FactorySalesDraft => ({ requestId: createUuid(), revision: 1, customerId: null, warehouseId: null, businessType: props.businessType, currencyCode: 'EUR', factoryName: 'Totem', contractNo: '', goodsDescription: '', netAmount: null, vatAmount: null, paymentTerms: '先付款后提货，客户直接向工厂付款。', paymentDueDate: null, pickupDate: null, pickupAddress: '', pickupContact: '', preparationNote: '', deliveryNote: '', collectionNote: '', remark: '', orderIds: [] })
const form = reactive(blank())
const currentStep = computed(() => factorySteps.find(step=>!detail.value?.steps[step.code])?.code || 'CLOSED')
const availableOrders = computed(() => options.value.orders.filter(order => order.customerId === form.customerId))
const warnings = computed(() => [
  !form.contractNo && '合同号尚未填写，可后补。', !form.goodsDescription && '商品及数量尚未填写，可后补。',
  localSale.value && (form.vatAmount === null || form.vatAmount === '') && '当地内销 VAT 金额待补充，以工厂合同为准。',
  !localSale.value && Number(form.vatAmount || 0) > 0 && '此场景通常不含 VAT，请核对所填税额。',
  !detail.value?.steps.COLLECTION && '工厂直签先付款后提货，请在提货前人工确认工厂已收款；金额及凭证可后补。',
].filter(Boolean))
const financialFields = [['netAmount','未税合同金额'],['vatAmount','VAT 金额']] as const
function message(e: unknown) { return api.isAxiosError(e) ? e.response?.data?.message || '操作失败，请重试' : e instanceof Error ? e.message : '操作失败，请重试' }
let listGeneration = 0, optionGeneration = 0
async function load() {
  const token=++listGeneration; busy.value=true; error.value=''
  try { const result=await europeSalesApi.list({ keyword:keyword.value, businessType:props.businessType,page:page.value,pageSize:20 }); if(token===listGeneration) {rows.value=result.records;total.value=result.total} }
  catch(e) { if(token===listGeneration) error.value=message(e) } finally { if(token===listGeneration) busy.value=false }
}
async function loadOptions(query = '') {
  if(!canWrite.value) return
  const token=++optionGeneration; optionsBusy.value=true
  try { const result=await piApi.options(query); if(token===optionGeneration) options.value=result }
  catch(e) { if(token===optionGeneration) formError.value=message(e) } finally { if(token===optionGeneration) optionsBusy.value=false }
}
function fill(order: FactorySalesOrder) { detail.value=order;Object.assign(form,blank(),structuredClone(order.details),{revision:order.revision});selectedStep.value=currentStep.value }
function create() { detail.value=null;Object.assign(form,blank());selectedStep.value='CONTRACT';formError.value='';visible.value=true;void loadOptions() }
async function open(row: FactorySalesOrder) {
  formError.value=''; saving.value=true
  try { fill(await europeSalesApi.get(row.id));visible.value=true;await loadOptions() } catch(e){error.value=message(e)} finally{saving.value=false}
}
function payload(): FactorySalesDraft {
  if(!form.customerId) throw new Error('请选择客户')
  if(!/^[A-Z]{3}$/.test(form.currencyCode.trim().toUpperCase())) throw new Error('请填写三位币种代码，例如 EUR')
  for(const [field,label] of financialFields) if(form[field]!==null && form[field]!=='' && !/^(0|[1-9]\d{0,15})(\.\d{1,2})?$/.test(String(form[field]))) throw new Error(`${label}请填写有效金额，最多两位小数`)
  return {...form, currencyCode:form.currencyCode.trim().toUpperCase(),netAmount:form.netAmount===''?null:form.netAmount,vatAmount:form.vatAmount===''?null:form.vatAmount,orderIds:[...form.orderIds]}
}
async function save(advance=false) {
  if(saving.value || !canWrite.value) return
  saving.value=true; formError.value=''
  try {
    const step=selectedStep.value
    const saved=await europeSalesApi.save(detail.value?.id,payload());fill(saved)
    if(advance) {
      const note=step==='COLLECTION'?form.collectionNote:step==='PREPARATION'?form.preparationNote:step==='PICKUP'?form.deliveryNote:form.remark
      fill(await europeSalesApi.step(saved.id,step,saved.revision,true,note))
    }
    ElMessage.success(tr(advance?'已确认，继续下一步':'资料已保存'));await load()
  } catch(e) { formError.value=message(e) } finally {saving.value=false}
}
async function reopen(code: FactoryStep) {
  if(!detail.value || saving.value) return
  saving.value=true;formError.value=''
  try {updateProgress(await europeSalesApi.step(detail.value.id,code,detail.value.revision,false,'更正办理状态，恢复待核对'));await load()}
  catch(e){formError.value=message(e)}finally{saving.value=false}
}
async function close(done:()=>void) {
  if(saving.value)return
  try {
    const previous=detail.value?{...blank(),...detail.value.details,revision:detail.value.revision}:blank()
    const changed=detail.value?JSON.stringify(form)!==JSON.stringify(previous):Boolean(form.customerId||form.contractNo||form.goodsDescription)
    if(changed&&canWrite.value) await ElMessageBox.confirm(tr('尚未保存的输入将不保留。是否关闭？'),tr('关闭订单'),{type:'warning'})
    done()
  } catch(e){if(e!=='cancel'&&e!=='close')formError.value=message(e)}
}
async function refreshProgress() {
  const id=detail.value?.id
  if(!id) return
  try {
    const latest=await europeSalesApi.get(id)
    // 财务撤回会改变流程版本；保留当前尚未保存的表单输入。
    if(detail.value?.id===id) updateProgress(latest)
    await load()
  } catch(e) { formError.value=message(e) }
}
function updateProgress(latest:FactorySalesOrder) {
  if(!detail.value) return
  if(JSON.stringify(latest.details)!==JSON.stringify(detail.value.details)) throw new Error('订单资料已由其他操作更新，请保留当前输入并重新打开订单核对。')
  detail.value={...detail.value,steps:latest.steps,revision:latest.revision,orders:latest.orders}
  form.revision=latest.revision;selectedStep.value=currentStep.value
}
watch(()=>props.businessType,()=>{page.value=1;rows.value=[];void load()},{immediate:true})
</script>
<template>
  <section class="factory-workspace">
    <div class="factory-heading"><div><h2>{{ localSale?tr('捷克代理当地内销'):tr('工厂直签外销') }}</h2><p>{{ localSale?tr('工厂合同包含 VAT'):tr('按工厂外销合同约定不含 VAT') }}{{ trInline("；客户直接付工厂，先付款后提货，业务员无需制作 PI。") }}</p></div><el-button v-if="canWrite" type="primary" @click="create">{{ tr("新建工厂直签订单") }}</el-button></div>
    <form class="factory-toolbar" @submit.prevent="page=1;load()"><el-input v-model="keyword" clearable :placeholder='tr("搜索订单号、客户或工厂合同号")' :aria-label='tr("搜索工厂直签订单")' /><el-button type="primary" native-type="submit">{{ tr("查询") }}</el-button><el-button @click="load">{{ tr("刷新") }}</el-button></form>
    <el-alert v-if="error" :title="tr(error)" type="error" :closable="false" />
    <el-table :data="rows" v-loading="busy" :empty-text='tr("暂无工厂直签订单，可直接登记工厂合同和备货需求")'><el-table-column prop="orderNo" :label='tr("业务订单号")' min-width="190" /><el-table-column prop="customerName" :label='tr("客户")' min-width="150" /><el-table-column prop="details.contractNo" :label='tr("工厂合同号")' min-width="150" /><el-table-column prop="warehouseName" :label='tr("关联仓库")' min-width="130" /><el-table-column :label='tr("办理进度")' min-width="170"><template #default="{row}">{{ row.steps.CLOSED?tr('已确认结案'):factorySteps.find(s=>!row.steps[s.code])?.name }}</template></el-table-column><el-table-column prop="updatedAt" :label='tr("更新时间")' min-width="180" /><el-table-column :label='tr("操作")' width="90" fixed="right"><template #default="{row}"><el-button link type="primary" @click="open(row)">{{ tr("办理") }}</el-button></template></el-table-column></el-table>
    <el-pagination v-model:current-page="page" :total="total" :page-size="20" layout="prev,pager,next,total" @current-change="load" />
    <el-dialog v-model="visible" :title="detail?tr('{value0} · 工厂直签办理', { value0: detail.orderNo }):tr('新建工厂直签订单')" width="min(1060px,96vw)" top="4vh" append-to-body :close-on-click-modal="false" :before-close="close" destroy-on-close>
      <el-alert v-if="formError" :title="tr(formError)" type="error" :closable="false" />
      <div class="factory-progress"><button v-for="step in factorySteps" :key="step.code" type="button" :class="{active:selectedStep===step.code,done:detail?.steps[step.code]}" @click="selectedStep=step.code">{{detail?.steps[step.code]?'✓ ':''}}{{step.name}}</button></div>
      <el-alert type="info" :closable="false" :title='tr("填写后确认即可继续；工厂直签按先付款后提货办理。实际款项、系统出库分别记录。")' />
      <el-form class="factory-form" label-position="top" :disabled="!canWrite||saving">
        <h3>{{ tr("合同与商品") }}</h3>
        <el-form-item :label='tr("客户")' required><el-select v-model="form.customerId" filterable remote :remote-method="loadOptions" :loading="optionsBusy" :placeholder='tr("搜索并选择客户")'><el-option v-if="detail && !options.customers.some(c=>c.id===detail!.details.customerId)" :value="detail.details.customerId!" :label="detail.customerName" /><el-option v-for="c in options.customers" :key="c.id" :value="c.id" :label="`${c.code} · ${c.name}`" /></el-select></el-form-item>
        <el-form-item :label='tr("签约及收款工厂")'><el-input v-model="form.factoryName" maxlength="200" /></el-form-item>
        <el-form-item :label='tr("工厂合同号")'><el-input v-model="form.contractNo" maxlength="200" :placeholder='tr("可后补")' /></el-form-item>
        <el-form-item :label='tr("币种")' required><el-input v-model="form.currencyCode" maxlength="3" placeholder="EUR" /></el-form-item>
        <el-form-item :label='tr("商品、规格及数量")' class="wide"><el-input v-model="form.goodsDescription" type="textarea" :rows="3" maxlength="4000" :placeholder='tr("按工厂合同填写商品、配置、数量等信息；需要系统扫码出库时可关联已有销售订单。")' /></el-form-item>
        <el-form-item v-for="[field,label] in financialFields" :key="field" :label="tr(label)"><el-input v-model="form[field]" inputmode="decimal" :placeholder='tr("可后补")'><template #append>{{form.currencyCode}}</template></el-input></el-form-item>
        <h3>{{ tr("备货、工厂收款与提货") }}</h3>
        <el-form-item :label='tr("备货情况")' class="wide"><el-input v-model="form.preparationNote" type="textarea" maxlength="2000" /></el-form-item>
        <el-form-item :label='tr("付款约定")'><el-input v-model="form.paymentTerms" type="textarea" maxlength="1000" /></el-form-item>
        <el-form-item :label='tr("约定付款日期（选填）")'><el-date-picker v-model="form.paymentDueDate" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item :label='tr("工厂到账确认／催款情况")' class="wide"><el-input v-model="form.collectionNote" type="textarea" maxlength="2000" :placeholder='tr("收到工厂到账反馈后，选择“确认工厂已收款”并确认下一步。款项明细可在下方台账补录。")' /></el-form-item>
        <el-form-item :label='tr("提货日期")'><el-date-picker v-model="form.pickupDate" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item :label='tr("提货联系人")'><el-input v-model="form.pickupContact" maxlength="300" /></el-form-item>
        <el-form-item :label='tr("提货地址")' class="wide"><el-input v-model="form.pickupAddress" maxlength="1000" /></el-form-item>
        <el-form-item :label='tr("实际交接／运输及单据说明")' class="wide"><el-input v-model="form.deliveryNote" type="textarea" maxlength="2000" /></el-form-item>
        <h3>{{ tr("关联系统出库（选填）") }}</h3>
        <p class="wide">{{ tr("工厂直接交货可记录交接情况。使用系统库存时，先从库存与锁定建立销售订单，再在这里关联；确认本流程不会扣减库存。") }}</p>
        <el-form-item :label='tr("仓库")'><el-select v-model="form.warehouseId" clearable filterable><el-option v-for="w in options.warehouses||[]" :key="w.id" :value="w.id" :label="w.name" /></el-select></el-form-item>
        <el-form-item :label='tr("同一客户的销售订单")'><el-select v-model="form.orderIds" multiple filterable remote :remote-method="loadOptions" :loading="optionsBusy" :placeholder='tr("搜索销售订单号")'><el-option v-for="o in availableOrders" :key="o.id" :value="o.id" :label="tr('{value0} · {value1} 台', { value0: o.orderNo, value1: o.skuQuantity ?? '—' })" /><el-option v-for="o in detail?.orders.filter(o=>!availableOrders.some(a=>a.id===o.id))||[]" :key="o.id" :value="o.id" :label="o.orderNo" /></el-select></el-form-item>
        <el-form-item :label='tr("补充说明／合同或凭证参考信息")' class="wide"><el-input v-model="form.remark" type="textarea" maxlength="4000" /></el-form-item>
      </el-form>
      <div v-if="warnings.length" class="factory-warnings"><strong>{{ tr("待核对提示") }}</strong><p v-for="warning in warnings" :key="String(warning)">{{warning}}</p></div>
      <div v-if="detail" class="factory-records"><strong>{{ tr("确认记录") }}</strong><p v-for="(confirmation,code) in detail.steps" :key="code">{{tr(stepNames[code])}} · {{confirmation?.operatorName}} · {{confirmation?.confirmedAt}} <el-button v-if="canWrite" link type="warning" :disabled="saving" @click="reopen(code as FactoryStep)">{{ tr("恢复待核对") }}</el-button></p><p v-for="order in detail.orders" :key="order.id">{{order.orderNo}}{{ trInline("：订单") }} {{order.orderQty}} {{ trInline("台，实际出库") }} {{order.shippedQty}} {{ trInline("台") }} <el-button v-if="canOrders" link type="primary" @click="journeyOrderNo=order.orderNo">{{ tr("打开订单办理") }}</el-button></p><a v-if="canOrders" href="/workflow?stage=inventory" target="_blank" rel="noopener noreferrer">{{ tr("打开库存与锁定 ↗") }}</a></div>
      <SalesSettlementPanel v-if="detail&&canFinance" type="FACTORY" :subject-id="detail.id" :currency-code="detail.details.currencyCode" :context="context" @changed="refreshProgress" />
      <template #footer><el-button :disabled="saving" @click="close(()=>visible=false)">{{ tr("关闭") }}</el-button><el-button v-if="canWrite" :loading="saving" @click="save()">{{ tr("保存／补录资料") }}</el-button><el-button v-if="canWrite" type="primary" :loading="saving" @click="save(true)">{{selectedStep==='COLLECTION'?'':tr('确认')}}{{tr(stepNames[selectedStep])}}{{ trInline("，继续下一步") }}</el-button></template>
    </el-dialog>
    <el-dialog :model-value="Boolean(journeyOrderNo)" :title='tr("销售订单办理")' width="min(1100px,96vw)" append-to-body destroy-on-close @close="journeyOrderNo=''"><OrderJourney v-if="journeyOrderNo" :order-no="journeyOrderNo" :context="context" @close="journeyOrderNo=''" @changed="refreshProgress" /></el-dialog>
  </section>
</template>
<style scoped>.factory-heading{display:flex;justify-content:space-between;gap:16px;align-items:center;flex-wrap:wrap}.factory-heading h2{margin:0 0 8px}.factory-heading p,.factory-form p{color:#60758a;line-height:1.8}.factory-toolbar{display:flex;gap:10px;margin:18px 0}.factory-toolbar .el-input{max-width:460px}.factory-workspace>.el-pagination{margin-top:16px}.factory-form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 20px;margin-top:18px}.factory-form h3,.factory-form .wide{grid-column:1/-1}.factory-form h3{padding-top:14px;border-top:1px solid #e3eaf0;color:#244b68}.factory-form :deep(.el-select),.factory-form :deep(.el-date-editor){width:100%}.factory-progress{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:15px}.factory-progress button{padding:10px;border:1px solid #c9d6e2;background:#fff;border-radius:6px;cursor:pointer;color:#244b68}.factory-progress .active{background:#e9f3ff;border-color:#3476bb}.factory-progress .done{color:#238250}.factory-warnings{padding:12px 16px;background:#fff7e9;border-radius:6px;margin:12px 0}.factory-warnings p{margin:5px 0;line-height:1.7}.factory-records{padding:16px 0;border-top:1px solid #e3eaf0}.factory-records p{line-height:1.8;overflow-wrap:anywhere}@media(max-width:600px){.factory-form{grid-template-columns:1fr}.factory-toolbar{flex-wrap:wrap}.factory-progress button{flex:1 1 40%}}</style>
