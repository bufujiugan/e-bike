<script setup lang="ts">
import { tr, trInline } from '../i18n/language'
import RecordIdColumn from './table/RecordIdColumn.vue'
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { hasAnyPermission, hasPermission, hasRole, type PermissionContext } from '../permissions'
import { reservationLabel } from '../pi-api'
import RecordDetails from './RecordDetails.vue'
import PurchaseContractNumber from './PurchaseContractNumber.vue'
import PiWorkspace from './PiWorkspace.vue'
import AppIcon from './AppIcon.vue'
import OrderPurchaseContract from './OrderPurchaseContract.vue'
import { salesTypeLabel, salesTypeOptions, type SalesIntent, type SalesType } from '../sales-routing'

const props = defineProps<{ orderNo: string; context: PermissionContext | null }>()
const emit = defineEmits<{ close: []; changed: []; 'continue-pi': [intent: SalesIntent] }>()
const order = ref<any>(), sourceLock = ref<any>(), busy = ref(false), saving = ref(false), error = ref(''), lockError = ref('')
const showPi = ref(false)
const showContract = ref(false)
const contractOpened = ref(false)
const contractPreparation = ref<{ available: boolean; reason?: string; hasLinkedPi: boolean }>()
const contractError = ref('')
// 本窗口内是否已下载过购销合同；仅用于确认前提醒，不作为出库门槛。
const contractDownloaded = ref(false)
const typeChoice = ref<SalesType | ''>(''), europeTradeType = ref<'DOMESTIC' | 'EXPORT'>('EXPORT'), typeSaving = ref(false)
const salesType = computed<SalesType | null>(() => order.value?.salesType ?? null)
const active = computed(() => Boolean(order.value) && !['CANCELLED', 'CLOSED'].includes(order.value.status))
const beforeConfirm = computed(() => ['DRAFT', 'PENDING'].includes(order.value?.status))
const canChooseType = computed(() => internal.value && hasPermission(props.context, 'ORDER_WRITE') && active.value && !salesType.value)
const needsPi = computed(() => (salesType.value === 'EXPORT' || salesType.value === 'EUROPE') && !hasLinkedPi.value && active.value)
const canPrepareContract = computed(() => internal.value && hasPermission(props.context, 'ORDER_READ') && hasPermission(props.context, 'ORDER_WRITE'))
const canGenerateContract = computed(() => canPrepareContract.value && hasPermission(props.context, 'ORDER_WRITE') && contractPreparation.value?.available === true)
const hasLinkedPi = computed(() => (contractPreparation.value?.hasLinkedPi ?? order.value?.hasLinkedPi) === true)
let version = 0
const internal = computed(() => !hasRole(props.context, 'WEB_AGENT') && !hasRole(props.context, 'PDA_OPERATOR'))
// 外销、欧洲订单由对应 PI 确认时一并确认；待选类型须先选择。
const canConfirm = computed(() => internal.value && hasPermission(props.context, 'ORDER_WRITE') && beforeConfirm.value && salesType.value === 'DOMESTIC')
const names: Record<string, string> = { DRAFT: '草稿', PENDING: '待确认', CONFIRMED: '待出库', PARTIAL_SHIPPED: '部分出库', SHIPPED: '已完成', CANCELLED: '已作废', CLOSED: '已关闭' }
const journeyStages = computed(() => {
  const status = order.value?.status
  if (!status || ['CANCELLED', 'CLOSED'].includes(status)) return []
  const current = status === 'SHIPPED' ? 2 : ['CONFIRMED', 'PARTIAL_SHIPPED'].includes(status) ? 1 : 0
  return ['核对并确认订单', '核对放行与出库', '完成交付核对'].map((title, index) => ({ title, state: index < current ? 'done' : index === current ? 'current' : 'waiting' }))
})
const time = (value?: string) => value ? value.replace('T', ' ').slice(0, 19) : '—'
const nextStep = computed(() => {
  const current = order.value
  if (!current) return ''
  if (active.value && !salesType.value) return tr('下一步：先选择本订单的销售类型。系统不会根据客户或币种判断内外销。')
  if (needsPi.value) return tr('下一步：本订单为「{value0}」，请到对应菜单生成 PI 并关联本订单；PI 确认后订单随之确认。', {
    value0: tr(salesTypeLabel(salesType.value)),
  })
  if (beforeConfirm.value) return salesType.value === 'DOMESTIC'
    ? tr('下一步：先生成并核对购销合同，再核对客户、数量与库存占用，确认销售订单。')
    : tr('下一步：核对已关联 PI 的确认状态；PI 确认后订单随之确认。')
  if (['CONFIRMED', 'PARTIAL_SHIPPED'].includes(current.status)) return hasLinkedPi.value
    ? tr('下一步：核对库存占用和已关联 PI 的发货放行，再由 PDA 按本订单扫码出库。')
    : tr('下一步：核对库存占用与出库条件，再由 PDA 按本订单扫码出库。')
  if (current.status === 'SHIPPED') return tr('本订单已完成出库，可以在下方核对每台车及出库单。')
  return tr('该订单已停止履约，请核对订单状态及历史记录。')
})
async function load() {
  const current = ++version
  busy.value = true; error.value = ''; lockError.value = ''; order.value = undefined; sourceLock.value = undefined
  contractPreparation.value = undefined; contractError.value = ''; showPi.value = false; typeChoice.value = ''
  try {
    const result = (await api.get(`/api/orders/by-no/${encodeURIComponent(props.orderNo)}`)).data.data
    if (current !== version) return
    order.value = result
    // 购销合同只在第一步（确认订单前）填写；确认后的出库页面不再加载合同。
    if (canPrepareContract.value && result.salesType === 'DOMESTIC' && ['DRAFT', 'PENDING'].includes(result.status)) {
      try {
        const preparation = (await api.get(`/api/orders/${result.id}/purchase-contract`)).data.data
        if (current === version) contractPreparation.value = preparation
      } catch (cause) {
        if (current === version) contractError.value = api.isAxiosError(cause) ? cause.response?.data?.message || '合同入口加载失败，请刷新重试' : '合同入口加载失败，请刷新重试'
      }
    }
    if (result.sourceLockNo && hasAnyPermission(props.context, ['AGENT_LOCK_WRITE','AGENT_INVENTORY_READ']) && internal.value) {
      try {
        const lock = (await api.get(`/api/inventory-locks/by-no/${encodeURIComponent(result.sourceLockNo)}`)).data.data
        if (current === version) sourceLock.value = lock
      } catch (cause) { if (current === version) lockError.value = api.isAxiosError(cause) ? cause.response?.data?.message ?? '来源锁定加载失败' : '来源锁定加载失败' }
    }
  } catch (cause) { if (current === version) error.value = api.isAxiosError(cause) ? cause.response?.data?.message ?? '订单加载失败' : '订单加载失败' }
  finally { if (current === version) busy.value = false }
}
async function confirm() {
  if (!canConfirm.value || saving.value) return
  const reminder = contractDownloaded.value ? '' : trInline('本窗口尚未生成购销合同，建议先生成并核对合同。')
  try { await ElMessageBox.confirm(tr("{value0}确认订单 {value1}，共 {value2} 台？确认后进入出库环节，不再显示购销合同填写页，请先下载保存合同。", { value0: reminder, value1: order.value.orderNo, value2: order.value.orderQty }), tr('确认销售订单'), { type: 'warning' }) }
  catch { return }
  saving.value = true
  try {
    await api.post(`/api/orders/${order.value.id}/confirm`)
    ElMessage.success(tr('订单已确认，请继续核对发货条件')); emit('changed'); await load()
  } catch (cause) { ElMessage.error(tr(api.isAxiosError(cause) ? cause.response?.data?.message ?? '订单确认失败，请刷新核对状态' : '订单确认失败，请刷新核对状态')) }
  finally { saving.value = false }
}
async function chooseSalesType() {
  const selected = typeChoice.value
  if (!canChooseType.value || !selected || typeSaving.value) return
  try { await ElMessageBox.confirm(tr("将订单 {value0} 确定为「{value1}」？选定后不可修改。", { value0: order.value.orderNo, value1: tr(salesTypeLabel(selected)) }), tr('确定销售类型'), { type: 'warning' }) }
  catch { return }
  typeSaving.value = true
  try {
    await api.post(`/api/orders/${order.value.id}/sales-type`, { salesType: selected })
    ElMessage.success(tr("已确定为「{value0}」", { value0: tr(salesTypeLabel(selected)) })); emit('changed'); await load()
  } catch (cause) { ElMessage.error(tr(api.isAxiosError(cause) ? cause.response?.data?.message ?? '销售类型保存失败，请刷新核对' : '销售类型保存失败，请刷新核对')) }
  finally { typeSaving.value = false }
}
function continuePi() {
  const current = order.value
  if (!current || (salesType.value !== 'EXPORT' && salesType.value !== 'EUROPE')) return
  emit('continue-pi', { kind: 'order', salesType: salesType.value, europeTradeType: salesType.value === 'EUROPE' ? europeTradeType.value : undefined, orderId: current.id, orderNo: current.orderNo, customerId: current.customerId, customerName: current.customerName })
}
function toggleContract() { showContract.value = !showContract.value; contractOpened.value = true }
watch(() => props.orderNo, () => { showPi.value = false; showContract.value = false; contractOpened.value = false; contractDownloaded.value = false; void load() }, { immediate: true })
</script>

<template>
  <el-dialog :model-value="true" :title='tr("订单办理 · 从确认到出库")' width="min(1120px, 96vw)" :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving" @close="emit('close')">
    <el-alert v-if="error" type="error" :title="tr(error)" :closable="false" />
    <div v-loading="busy" class="order-journey">
      <template v-if="order">
        <div class="journey-heading"><div><span class="journey-eyebrow">{{ tr("当前办理订单") }}</span><h2>{{ order.orderNo }}</h2><p>{{ order.customerName }} · {{ order.modelName }}</p></div><div class="journey-heading-actions"><el-tag :type="salesType ? 'success' : 'warning'">{{ tr(salesTypeLabel(salesType)) }}</el-tag><el-tag>{{ tr(names[order.status] ?? order.status) }}</el-tag><el-button :loading="busy" @click="load">{{ tr("刷新进度") }}</el-button></div></div>
        <ol v-if="journeyStages.length" class="journey-stages" :aria-label='tr("订单办理进度")'><li v-for="(step, index) in journeyStages" :key="step.title" :class="step.state" :aria-current="step.state === 'current' ? 'step' : undefined"><span><AppIcon v-if="step.state === 'done'" name="check" /><template v-else>{{ index + 1 }}</template></span><strong>{{ tr(step.title) }}</strong></li></ol>
        <div class="journey-counts"><div>{{ tr("订购数量") }}<strong>{{ order.orderQty }} {{ tr("台") }}</strong></div><div>{{ tr("已出库") }}<strong>{{ order.shippedQty }} {{ tr("台") }}</strong></div><div>{{ tr("未出库") }}<strong>{{ Math.max(0, order.orderQty - order.shippedQty) }} {{ tr("台") }}</strong></div><div>{{ tr("库存占用") }}<strong>{{ tr(reservationLabel(order.reservationStatus)) }}</strong></div></div>
        <el-alert :title="nextStep" type="info" :closable="false" show-icon />
        <el-alert v-if="order.status === 'CANCELLED' && order.remark?.startsWith('自动取消：')" :title='tr("订单已自动取消")' :description="order.remark" type="warning" :closable="false" show-icon />
        <section v-if="active && !salesType" class="journey-step-card" :aria-label='tr("选择销售类型")'>
          <h3>{{ tr("选择销售类型") }}</h3>
          <p>{{ tr("本订单尚未确定内销或外销。请按实际业务选择，选定后不可修改；内销生成购销合同，外销与欧洲在对应菜单办理 PI。") }}</p>
          <div class="lock-sales-types" role="radiogroup" :aria-label='tr("销售类型")'>
            <button v-for="option in salesTypeOptions" :key="option.value" type="button" role="radio" :aria-checked="typeChoice === option.value" :class="{ active: typeChoice === option.value }" :disabled="!canChooseType" @click="typeChoice = option.value"><strong>{{ tr(option.title) }}</strong><small>{{ tr(option.menu) }} {{ trInline("· 模板：") }}{{ option.template }}</small><span>{{ option.value === 'DOMESTIC' ? tr('在本窗口生成并核对购销合同，再确认订单。') : tr('确定后前往对应菜单生成 PI 并关联本订单。') }}</span></button>
          </div>
          <el-button v-if="canChooseType" type="primary" :loading="typeSaving" :disabled="!typeChoice" @click="chooseSalesType">{{ tr("确定销售类型") }}</el-button>
          <el-alert v-else :title='tr("请联系有订单维护权限的业务员选择销售类型。")' type="warning" :closable="false" />
        </section>
        <section v-else-if="needsPi" class="journey-step-card">
          <h3>{{ salesType === 'EXPORT' ? tr('外销 · PI') : tr('欧洲 · PI') }}</h3>
          <p>{{ trInline("本订单不生成国内购销合同。请到「") }}{{ salesType === 'EXPORT' ? tr('销售订单 → 外销') : tr('欧洲销售与交付 → 正常 PI 销售') }}{{ trInline("」新建 PI 并关联本订单，单据使用") }} {{ salesType === 'EXPORT' ? tr('260728英国builtPI') : 'France_1104_PI' }} {{ trInline("模板。") }}</p>
          <el-radio-group v-if="salesType === 'EUROPE'" v-model="europeTradeType" :aria-label='tr("欧洲贸易类型")'><el-radio-button value="DOMESTIC">{{ tr("欧洲内销") }}</el-radio-button><el-radio-button value="EXPORT">{{ tr("欧洲外销") }}</el-radio-button></el-radio-group>
          <div class="journey-actions"><el-button v-if="hasPermission(context, 'PI_WRITE')" type="primary" @click="continuePi">{{ tr("前往生成 PI 并关联本订单") }}</el-button></div>
        </section>
        <section v-else-if="salesType === 'DOMESTIC' && beforeConfirm" class="journey-step-card">
          <h3>{{ tr("第一步：生成购销合同，再确认订单") }}</h3>
          <el-alert v-if="contractError" :title="tr(contractError)" type="error" :closable="false"><el-button text @click="load">{{ tr("重试") }}</el-button></el-alert>
          <el-alert v-else-if="contractPreparation && !contractPreparation.available" :title="contractPreparation.reason || tr('当前订单不适用购销合同')" type="info" :closable="false" />
          <div class="journey-actions">
            <el-button v-if="canGenerateContract" type="primary" @click="toggleContract">{{ showContract ? tr('收起购销合同') : tr('① 生成购销合同') }}</el-button>
            <el-button v-if="canConfirm" :type="contractDownloaded ? 'primary' : 'default'" :loading="saving" @click="confirm">{{ tr("② 确认订单") }}</el-button>
          </div>
          <OrderPurchaseContract v-if="contractOpened && canGenerateContract" v-show="showContract" :order="order" @downloaded="contractDownloaded = true" />
        </section>
        <div v-if="hasLinkedPi && hasPermission(context, 'PI_READ')" class="journey-actions"><el-button @click="showPi = !showPi">{{ showPi ? tr('收起已关联 PI') : tr('查看已关联 PI 与履约') }}</el-button></div>
        <p v-if="['CONFIRMED','PARTIAL_SHIPPED'].includes(order.status)">{{ trInline("PDA：进入「出库」，选择客户「") }}{{ order.customerName }}{{ trInline("」，搜索订单「") }}{{ order.orderNo }}{{ trInline("」，核对后扫描整车总码。") }}<template v-if="hasLinkedPi">{{ tr("已关联 PI 的订单仍需完成原 PI 的确认、到账登记与发货放行。") }}</template></p>
        <PiWorkspace v-if="showPi && hasLinkedPi" :context="context" :initial-keyword="order.orderNo" :allow-create="false" />
        <el-descriptions :title='tr("订单与来源")' :column="1" border>
          <el-descriptions-item :label='tr("销售类型")'>{{ tr(salesTypeLabel(salesType)) }}<template v-if="salesType && !order.selectedSalesType"> {{ tr("（按已关联单据归类）") }}</template></el-descriptions-item>
          <el-descriptions-item v-if="internal" :label='tr("购销合同方")'>{{ order.purchaseContractParty === 'YINSANHUAN' ? tr('银三环') : order.purchaseContractParty || tr('未登记') }}</el-descriptions-item>
          <el-descriptions-item v-if="internal" :label='tr("购销合同号")'><PurchaseContractNumber :value="order.purchaseContractNo" /></el-descriptions-item>
          <el-descriptions-item :label='tr("来源锁定")'>{{ order.sourceLockNo || tr('无来源锁定') }}<span v-if="sourceLock"> · {{ sourceLock.skuCode || sourceLock.modelName }} {{ trInline("· 锁定") }} {{ sourceLock.lockQty }} {{ trInline("台 · 已转") }} {{ sourceLock.convertedQty }} {{ tr("台") }}</span></el-descriptions-item>
          <el-descriptions-item :label='tr("计划出库日")'>{{ order.plannedShipDate || '—' }}</el-descriptions-item>
          <el-descriptions-item :label="order.status === 'CANCELLED' ? tr('取消说明 / 备注') : tr('备注')">{{ order.remark || '—' }}</el-descriptions-item>
          <el-descriptions-item :label='tr("创建 / 修改")'>{{ time(order.createdAt) }} / {{ time(order.updatedAt) }}</el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="lockError" :title="tr(lockError)" type="warning" :closable="false" />
        <h3>{{ trInline("本单已出库车辆（") }}{{ order.shippedVehicles?.length ?? 0 }}）</h3>
        <el-table :data="order.shippedVehicles ?? []" max-height="360" :empty-text='tr("本单尚无有效出库车辆")'>
          <RecordIdColumn prop="vehicleId" :label='tr("整车 ID")' />
          <el-table-column prop="vehicleQrCode" :label='tr("总码")' min-width="180" /><el-table-column prop="frameSn" :label='tr("车架码")' min-width="170" /><el-table-column prop="stockOutNo" :label='tr("出库单号")' min-width="180" /><el-table-column :label='tr("出库时间")' min-width="170"><template #default="{row}">{{ time(row.stockOutTime) }}</template></el-table-column>
        </el-table>
        <el-collapse><el-collapse-item :title='tr("完整订单信息")' name="details"><RecordDetails :value="order" /></el-collapse-item></el-collapse>
      </template>
    </div>
    <template #footer><el-button v-if="error" @click="load">{{ tr("重试") }}</el-button><el-button :disabled="saving" @click="emit('close')">{{ tr("返回工作区") }}</el-button></template>
  </el-dialog>
</template>

<style scoped>
.journey-eyebrow{display:block;margin-bottom:8px;color:#8192a5;font-size:11px;letter-spacing:1px}.journey-heading{padding:18px;background:#f6f9fd;border:1px solid #e4ecf5;border-radius:10px}.journey-heading h2{font-size:21px}.journey-heading p{margin-bottom:0}.journey-heading-actions{display:flex;align-items:center;gap:12px}.journey-stages{display:flex;list-style:none;gap:12px;padding:0;margin:20px 0}.journey-stages li{display:flex;align-items:center;gap:9px;flex:1;color:#8a9aab;font-size:12px}.journey-stages li:not(:last-child)::after{content:'';height:1px;background:#e1e8f0;flex:1;margin-left:8px}.journey-stages li>span{display:grid;place-items:center;width:27px;height:27px;border-radius:50%;background:#f0f3f7;flex-shrink:0}.journey-stages .current{color:#2368cb}.journey-stages .current>span{background:#2368cb;color:white}.journey-stages .done{color:#168775}.journey-stages .done>span{background:#edf8f4}.journey-stages .app-icon{width:17px;height:17px}.journey-stages strong{font-weight:500}@media(max-width:600px){.journey-stages{flex-direction:column}.journey-stages li:not(:last-child)::after{display:none}}
.order-journey :deep(.pi-workspace){width:100%;max-width:100%;box-sizing:border-box;padding:16px;margin:16px 0}.order-journey :deep(.pi-heading){margin:0 0 16px;padding:12px 0}.order-journey :deep(.pi-filters){flex-wrap:wrap}.order-journey :deep(.pi-filters>*){max-width:100%}
.order-journey{min-height:120px;overflow-wrap:anywhere}.journey-heading,.journey-actions{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.journey-heading h2{margin:0}.journey-heading p,.order-journey>p{color:#64748b;line-height:1.8}.journey-counts{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin:18px 0}.journey-counts>div{background:#f1f6fb;padding:14px;border-radius:10px}.journey-counts strong{display:block;margin-top:8px;color:#1765ad}.journey-actions{justify-content:flex-start;margin:16px 0}.journey-step-card{margin:16px 0;padding:16px 18px;border:1px solid #ead9c8;border-radius:12px;background:#fffaf5}.journey-step-card h3{margin:0 0 6px;font-size:16px}.journey-step-card>p{margin:0 0 10px;color:#64748b;line-height:1.7}.el-collapse{margin-top:18px}@media(max-width:600px){.journey-counts{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style>
