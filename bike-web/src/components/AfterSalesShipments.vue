<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { afterSales, afterSalesError, blankShipping, shippingFromShipment, type AfterSalesCase, type CaseShipment, type LogisticsRequest } from '../after-sales-api'
import AfterSalesShippingFields from './AfterSalesShippingFields.vue'
const props = defineProps<{ record: AfterSalesCase; canShip: boolean; canReview: boolean }>()
const emit = defineEmits<{ changed: []; dispatch: [row: CaseShipment]; reconcile: [row: CaseShipment] }>()
const active = computed(() => props.canShip && ['APPROVED','IN_PROGRESS','WAITING_PARTS','SHIPPED','RESOLVED'].includes(props.record.status))
const visible = ref(false), saving = ref(false), selected = ref<CaseShipment | null>(null)
const shipping = ref(blankShipping())
const form = reactive<LogisticsRequest>({ revision: 1, action: 'SAVE', carrier: '', trackingNo: '', logisticsStatus: 'IN_TRANSIT', note: '', expectedArrivalDate: null })
const actions = { SAVE: '编辑发运资料', TRACK: '更新物流进度', CORRECT: '更正运单', CANCEL: '取消补发计划' }
const states = { PLANNED: '待发货', SHIPPED: '已发货', CANCELLED: '已取消' }
const logistics = { IN_TRANSIT: '运输中', EXCEPTION: '运输异常', DELIVERED: '已签收' }
const time = (s: string | null) => s ? s.replace('T',' ').slice(0,19) : '—'
function open(row: CaseShipment, action: LogisticsRequest['action']) {
  selected.value = row; shipping.value = shippingFromShipment(row)
  Object.assign(form, { revision: row.revision, action, carrier: row.carrier || '', trackingNo: row.trackingNo || '', logisticsStatus: row.logisticsStatus || 'IN_TRANSIT', note: '', expectedArrivalDate: row.expectedArrivalDate })
  visible.value = true
}
async function save() {
  if (!selected.value || saving.value) return
  if (form.action !== 'SAVE' && !form.note.trim()) { ElMessage.warning('请填写物流说明或变更原因'); return }
  saving.value = true
  try { await afterSales.logistics(props.record.id, selected.value.id, { ...form, shipping: form.action === 'SAVE' ? { ...shipping.value } : undefined }); visible.value = false; emit('changed'); ElMessage.success('发运资料已更新') }
  catch (e) { ElMessage.error(afterSalesError(e)); visible.value = false; emit('changed') }
  finally { saving.value = false }
}
watch(() => props.record.id, () => { visible.value = false; selected.value = null })
</script>
<template>
  <section aria-label="配件发运记录">
    <el-empty v-if="!record.shipments.length" description="暂无补发记录" />
    <article v-for="row in record.shipments" :key="row.id" class="shipment">
      <header><strong>{{ row.partCode || '历史编号未核对' }} · {{ row.partName }} × {{ row.quantity }}</strong><el-tag>{{ states[row.status] }}</el-tag></header>
      <dl>
        <div><dt>发货仓库</dt><dd>{{ row.warehouseName || '未登记' }}</dd></div>
        <div><dt>收货人 / 电话</dt><dd>{{ row.recipient || '未登记' }} / {{ row.phone || '未登记' }}</dd></div>
        <div class="wide"><dt>收货地址 / 邮编</dt><dd>{{ [row.countryCode,row.region,row.city,row.addressLine].filter(Boolean).join(' · ') || '未登记' }} / {{ row.postalCode || '—' }}</dd></div>
        <div><dt>承运商 / 运单号</dt><dd>{{ row.carrier || '—' }} / {{ row.trackingNo || '—' }}</dd></div>
        <div><dt>实际发货 / 预计到达</dt><dd>{{ time(row.shippedAt) }} / {{ row.expectedArrivalDate || '—' }}</dd></div>
        <div><dt>物流状态 / 签收登记</dt><dd>{{ row.logisticsStatus ? logistics[row.logisticsStatus] : '未登记' }} / {{ time(row.deliveredAt) }}</dd></div>
        <div><dt>旧件 / 审批关联</dt><dd>{{ row.oldPartReturnRequired ? '需要退回' : '无需退回' }} / {{ row.approvalSource === 'LEGACY_UNLINKED' ? '历史待核对' : row.approvalSource === 'LEGACY_REVIEWED' ? '历史已核对' : '已审批配件' }}</dd></div>
        <div v-if="row.logisticsNote || row.cancelledReason" class="wide"><dt>物流说明 / 取消原因</dt><dd>{{ row.cancelledReason || row.logisticsNote }}</dd></div>
      </dl>
      <div class="actions">
        <template v-if="active && row.status === 'PLANNED'">
          <el-button @click="open(row,'SAVE')">编辑发运资料</el-button><el-button type="primary" @click="emit('dispatch',row)">登记发货</el-button><el-button type="danger" plain @click="open(row,'CANCEL')">取消计划</el-button>
        </template>
        <template v-if="active && row.status === 'SHIPPED'">
          <el-button v-if="row.logisticsStatus !== 'DELIVERED'" @click="open(row,'TRACK')">更新物流进度</el-button><el-button @click="open(row,'CORRECT')">更正运单</el-button>
        </template>
        <el-button v-if="canReview && record.status === 'UNDER_REVIEW' && record.solutionVersion?.status === 'DRAFT' && row.approvalSource === 'LEGACY_UNLINKED' && row.status !== 'CANCELLED'" @click="emit('reconcile',row)">关联审批条目</el-button>
      </div>
    </article>
    <el-dialog v-model="visible" :title="actions[form.action]" width="min(720px,96vw)" append-to-body :close-on-click-modal="false" destroy-on-close>
      <el-form label-position="top" @submit.prevent="save">
        <AfterSalesShippingFields v-if="form.action === 'SAVE'" v-model="shipping" />
        <template v-if="form.action === 'CORRECT'"><el-form-item label="承运商" required><el-input v-model="form.carrier" maxlength="128" /></el-form-item><el-form-item label="运单号" required><el-input v-model="form.trackingNo" maxlength="128" /></el-form-item></template>
        <el-form-item v-if="form.action === 'TRACK'" label="物流状态" required><el-select v-model="form.logisticsStatus"><el-option v-for="(label,key) in logistics" :key="key" :value="key" :label="label" /></el-select></el-form-item>
        <el-form-item v-if="['TRACK','CORRECT'].includes(form.action)" label="预计到达日期"><el-date-picker v-model="form.expectedArrivalDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-alert v-if="form.action === 'CANCEL'" title="仅取消尚未发货且旧件退回未启动的计划。取消后可重新安排批准数量，原记录仍保留。" type="warning" :closable="false" />
        <el-form-item label="物流说明 / 变更原因（客户可见）" :required="form.action !== 'SAVE'"><el-input v-model="form.note" type="textarea" maxlength="1000" :rows="3" /></el-form-item>
        <el-button native-type="submit" type="primary" :loading="saving">保存</el-button>
      </el-form>
    </el-dialog>
  </section>
</template>
<style scoped>
.shipment{border:1px solid #e0e7ee;border-radius:8px;padding:18px;margin-bottom:16px}.shipment header{display:flex;justify-content:space-between;gap:14px}.shipment dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.wide{grid-column:1/-1}dt{font-size:12px;color:#6f7f8e;margin-bottom:5px}dd{margin:0;overflow-wrap:anywhere;white-space:pre-wrap;line-height:1.6}.actions{display:flex;gap:8px;flex-wrap:wrap}.actions .el-button{margin-left:0}@media(max-width:600px){.shipment dl{grid-template-columns:1fr}}
</style>
