<script setup lang="ts">
import { tr, trInline } from '../i18n/language'
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import api from '../api'

type Party = { name: string; address: string; contact: string; phone: string; fax: string; email: string }
type Contract = {
  contractNo: string; signedDate: string; orderUpdatedAt: string; modelId: number; orderQty: number; buyer: Party; seller: Party
  customsModel: string; productName: string; appearance: string; unitPrice: number | null
  usdUnitPrice: number | null; costUnitPrice: number | null; paymentTerms: string
  bankName: string; bankAccount: string; clauses: string[]
  skuPrices?: { skuId: number; skuCode: string; modelName: string; quantity: number; unitPrice: number | null }[]
}
const props = defineProps<{ order: { id: number; orderNo: string; modelName: string; orderQty: number; updatedAt: string } }>()
const emit = defineEmits<{ downloaded: [] }>()
const contract = ref<Contract>(), form = ref<FormInstance>(), loading = ref(false), exporting = ref(false)
const error = ref(''), unavailable = ref(''), reviewed = ref(false), errorSummary = ref<HTMLElement>()
let version = 0
const contactFields: { key: keyof Party; label: string; max: number }[] = [
  { key: 'address', label: '地址', max: 160 }, { key: 'contact', label: '联系人', max: 40 },
  { key: 'phone', label: '电话', max: 60 }, { key: 'fax', label: '传真', max: 60 }, { key: 'email', label: '邮箱', max: 100 },
]
const clauseLabels = ['合同说明', '订单回复约定', '价格说明', '交货地点', '交货方式', '包装方式', '客诉期', '质量与验收', '违约责任', '争议处理', '数量变更', '合同生效']
const required = (label: string) => ({ required: true, message: tr('请填写{field}', { field: tr(label) }), trigger: 'blur' })
const skuPrices = computed(() => contract.value?.skuPrices ?? [])
const rules = computed<FormRules>(() => ({
  contractNo: [required('合同编号')], signedDate: [required('签订日期')],
  'seller.name': [required('供方名称')], productName: [required('品名')],
  unitPrice: skuPrices.value.length ? [] : [required('人民币单价'), { type: 'number', min: 0.01, message: tr('单价必须大于零'), trigger: 'change' }],
  paymentTerms: [required('付款周期')],
}))
const total = computed(() => ((skuPrices.value.length
  ? skuPrices.value.reduce((sum, line) => sum + Math.round((line.unitPrice || 0) * 100) * line.quantity, 0)
  : Math.round((contract.value?.unitPrice || 0) * 100) * props.order.orderQty) / 100)
  .toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }))

async function load() {
  const current = ++version
  loading.value = true; error.value = ''; unavailable.value = ''; contract.value = undefined; reviewed.value = false
  try {
    const result = (await api.get(`/api/orders/${props.order.id}/purchase-contract`)).data.data
    if (current !== version) return
    if (!result.available) unavailable.value = result.reason
    else contract.value = result.contract
  } catch (cause) {
    if (current === version) error.value = api.isAxiosError(cause) ? cause.response?.data?.message || '合同资料加载失败，请重试' : '合同资料加载失败，请重试'
  } finally { if (current === version) loading.value = false }
}
async function exportContract() {
  if (!contract.value || exporting.value) return
  error.value = ''
  const valid = await form.value?.validate().catch(() => false)
  if (!valid || !reviewed.value) {
    error.value = !valid ? '请补全标记的必填项后重新生成。' : '请核对合同条款，并勾选确认。'
    await nextTick(); errorSummary.value?.focus(); return
  }
  exporting.value = true
  try {
    const response = await api.post<Blob>(`/api/orders/${props.order.id}/purchase-contract/export.xlsx`, contract.value, { responseType: 'blob' })
    const url = URL.createObjectURL(response.data), anchor = document.createElement('a')
    anchor.href = url; anchor.download = `购销合同-${contract.value.contractNo.replace(/[\\/:*?"<>|]/g, '_')}.xlsx`
    document.body.append(anchor); anchor.click(); anchor.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000)
    ElMessage.success(tr('购销合同已生成并开始下载'))
    emit('downloaded')
  } catch (cause) {
    error.value = '合同生成失败，请重试'
    if (api.isAxiosError(cause)) {
      const body = cause.response?.data
      if (body instanceof Blob) {
        try { error.value = JSON.parse(await body.text()).message || error.value }
        catch { error.value = '合同生成失败，服务器未返回有效错误信息，请稍后重试' }
      } else if (body?.message) error.value = body.message
    }
    await nextTick(); errorSummary.value?.focus()
  } finally { exporting.value = false }
}
watch(() => [props.order.id, props.order.updatedAt], () => { void load() }, { immediate: true })
</script>

<template>
  <section v-loading="loading" class="order-contract" :aria-label='tr("生成内销购销合同")'>
    <h3>{{ tr("内销购销合同") }}</h3>
    <p>{{ tr("使用「10.23金轮信德-广州塔威 购销合同」模板生成 Excel。客户、车型和数量取自订单；请补充并核对合同信息。本次填写仅保留在当前窗口，下载后请保存文件。") }}</p>
    <div v-if="error" ref="errorSummary" role="alert" tabindex="-1" class="contract-error">{{ tr(error) }}</div>
    <el-alert v-if="unavailable" :title="unavailable" type="info" :closable="false" />
    <el-button v-if="error && !contract" @click="load">{{ tr("重新加载合同资料") }}</el-button>
    <el-form v-if="contract" ref="form" :model="contract" :rules="rules" :validate-on-rule-change="false" label-position="top" novalidate :disabled="exporting" @submit.prevent="exportContract">
      <div class="contract-grid">
        <el-form-item :label='tr("合同编号")' prop="contractNo"><el-input v-model="contract.contractNo" maxlength="60" /></el-form-item>
        <el-form-item :label='tr("签订日期")' prop="signedDate"><el-date-picker v-model="contract.signedDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
      </div>
      <div class="contract-grid">
        <fieldset v-for="party in (['buyer', 'seller'] as const)" :key="party">
          <legend>{{ party === 'buyer' ? tr('需方（订单客户）') : tr('供方') }}</legend>
          <el-form-item :label="party === 'buyer' ? tr('需方名称') : tr('供方名称')" :prop="`${party}.name`"><el-input v-model="contract[party].name" :readonly="party === 'buyer'" maxlength="120" /></el-form-item>
          <el-form-item v-for="field in contactFields" :key="field.key" :label="tr(field.label)"><el-input v-model="contract[party][field.key]" :maxlength="field.max" /></el-form-item>
        </fieldset>
      </div>
      <h4>{{ tr("商品与金额") }}</h4>
      <p>{{ order.modelName }} · {{ order.orderQty }} {{ trInline("辆 · 合计人民币") }} <strong>¥{{ total }}</strong></p>
      <div class="contract-grid">
        <el-form-item :label='tr("报关型号")'><el-input v-model="contract.customsModel" maxlength="40" /></el-form-item>
        <el-form-item :label='tr("品名")' prop="productName"><el-input v-model="contract.productName" maxlength="60" /></el-form-item>
        <el-form-item v-if="!skuPrices.length" :label='tr("外观 / 颜色")'><el-input v-model="contract.appearance" maxlength="40" /></el-form-item>
        <el-form-item v-if="!skuPrices.length" :label='tr("单价（RMB）")' prop="unitPrice"><el-input-number v-model="contract.unitPrice" :min="0.01" :max="99999999.99" :precision="2" :step="0.01" :controls="false" /></el-form-item>
      </div>
      <div v-if="skuPrices.length" class="contract-grid">
        <el-form-item v-for="(line, index) in skuPrices" :key="line.skuId" :label="`${line.skuCode} · ${line.quantity} ${tr('台')}`"
          :prop="`skuPrices.${index}.unitPrice`" :rules="[required('人民币单价')]">
          <el-input-number v-model="line.unitPrice" :aria-label="line.skuCode + ' ' + tr('人民币单价')"
            :placeholder='tr("单价（RMB）")' :min="0.01" :max="99999999.99" :precision="2" :step="0.01" :controls="false" />
        </el-form-item>
      </div>
      <el-collapse v-else>
        <el-collapse-item :title='tr("模板辅助价格（选填，填写后将出现在合同中）")' name="prices"><div class="contract-grid">
          <el-form-item :label='tr("单价（USD，不参与人民币合计）")'><el-input-number v-model="contract.usdUnitPrice" :min="0" :max="99999999.99" :precision="2" :step="0.01" :controls="false" /></el-form-item>
          <el-form-item :label='tr("成本单价（RMB）")'><el-input-number v-model="contract.costUnitPrice" :min="0" :max="99999999.99" :precision="2" :step="0.01" :controls="false" /></el-form-item>
        </div></el-collapse-item>
      </el-collapse>
      <h4>{{ tr("付款与条款") }}</h4>
      <el-form-item :label='tr("付款周期")' prop="paymentTerms"><el-input v-model="contract.paymentTerms" maxlength="160" /></el-form-item>
      <div class="contract-grid">
        <el-form-item :label='tr("开户行")'><el-input v-model="contract.bankName" maxlength="120" /></el-form-item>
        <el-form-item :label='tr("银行账号")'><el-input v-model="contract.bankAccount" maxlength="80" /></el-form-item>
      </div>
      <p>{{ tr("以下文字沿用提供的模板，可按本单约定修改；付款公司名称及地址取自供方。") }}</p>
      <el-form-item v-for="(label, index) in clauseLabels" :key="label" :label="tr(label)" :prop="`clauses.${index}`" :rules="[required(label)]">
        <el-input v-model="contract.clauses[index]" type="textarea" :autosize="{ minRows: 1, maxRows: 4 }" maxlength="300" show-word-limit />
      </el-form-item>
      <el-checkbox v-model="reviewed">{{ tr("已核对双方信息、金额和合同条款") }}</el-checkbox>
      <div class="contract-actions"><el-button type="primary" native-type="submit" :loading="exporting">{{ tr("生成并下载购销合同（Excel）") }}</el-button></div>
    </el-form>
  </section>
</template>

<style scoped>
.order-contract{padding:18px;border:1px solid var(--el-border-color);border-radius:10px;margin:16px 0}
.order-contract p{color:var(--el-text-color-secondary);line-height:1.7}
.contract-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 20px}
fieldset{min-width:0;border:1px solid var(--el-border-color-light);border-radius:8px;padding:14px}
legend{font-weight:600;padding:0 6px}.contract-actions{margin-top:18px}
.contract-error{color:var(--el-color-danger);padding:12px;margin:12px 0;background:var(--el-color-danger-light-9)}
.order-contract :deep(.el-date-editor),.order-contract :deep(.el-input-number){width:100%}
.order-contract :deep(.el-checkbox){height:auto;white-space:normal}.order-contract :deep(.el-checkbox__label){white-space:normal}
@media(max-width:640px){.contract-grid{grid-template-columns:1fr}.order-contract{padding:12px}.contract-actions .el-button{height:auto;min-height:40px;white-space:normal}}
</style>
