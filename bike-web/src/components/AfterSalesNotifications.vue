<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { afterSales, afterSalesError, type CaseNotification, type NotificationPage } from '../after-sales-api'
const props = defineProps<{ caseId: number }>()
const result = ref<NotificationPage | null>(null), page = ref(1), loading = ref(false), error = ref(''), saving = ref(false), visible = ref(false)
const selected = ref<CaseNotification | null>(null), form = reactive({ recipient: '', reason: '' })
const states = { PENDING: '排队中', SENDING: '正在发送', RETRY: '等待自动重试', SENT: 'SMTP已接受', FAILED: '发送失败', BLOCKED: '缺少有效配置或地址' }
const events: Record<string,string> = { CASE_SUBMITTED: '提交案例 / 补充资料', REVIEW_REQUEST_INFO: '要求补充资料', REVIEW_APPROVE: '审批通过', REVIEW_REJECT: '申请被拒绝', REVIEW_REVISE: '方案重新审核', SHIPMENT_DISPATCHED: '配件发货', CASE_RESOLVED: '问题已解决', CASE_CLOSED: '案例归档', MESSAGE_ADDED: '新回复', ATTACHMENTS_ADDED: '新附件' }
const errors: Record<string,string> = { RECIPIENT_MISSING_OR_INVALID: '收件地址缺失或格式错误', SMTP_NOT_CONFIGURED: 'SMTP配置不完整', SMTP_SEND_FAILED: 'SMTP发送失败，请核对邮件服务', DELIVERY_RESULT_UNKNOWN: '发送结果不确定，重试前请核对邮箱，可能已经发出' }
const time = (s: string | null) => s ? s.replace('T',' ').slice(0,19) : '—'
let version = 0
async function load() {
  const current = ++version; loading.value = true; error.value = ''
  try { const data = await afterSales.notifications(props.caseId,page.value); if (current === version) result.value = data }
  catch (e) { if (current === version) { result.value = null; error.value = afterSalesError(e) } }
  finally { if (current === version) loading.value = false }
}
function open(row: CaseNotification) { selected.value = row; form.recipient = row.recipient || ''; form.reason = ''; visible.value = true }
async function retry() {
  if (!selected.value || saving.value) return
  if (!form.reason.trim()) { ElMessage.warning('请填写重试或更正原因'); return }
  saving.value = true
  try { await afterSales.retryNotification(props.caseId,selected.value.id,selected.value.revision,selected.value.audience === 'CUSTOMER' ? form.recipient : undefined,form.reason.trim()); visible.value = false; await load(); ElMessage.success('已重新排队，发送结果请刷新查看') }
  catch (e) { ElMessage.error(afterSalesError(e)); visible.value = false; await load() }
  finally { saving.value = false }
}
watch(() => props.caseId, () => { page.value = 1; visible.value = false; selected.value = null; result.value = null; void load() })
onMounted(load); onUnmounted(() => { version++ })
</script>
<template>
  <section aria-label="Email通知记录">
    <p>通知随业务操作自动排队。SMTP接受表示邮件服务器已接收，最终送达仍取决于收件邮箱。</p>
    <el-alert v-if="result && !result.sendingEnabled" title="邮件发送当前关闭，通知保留在队列中。启用前请核对SMTP和待发收件地址。" type="warning" :closable="false" />
    <el-alert v-else-if="result && !result.smtpConfigured" title="SMTP配置不完整，请由管理员配置邮件服务后重试。" type="warning" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-button :loading="loading" @click="load">刷新通知状态</el-button>
    <el-table :data="result?.notifications.records || []" v-loading="loading" empty-text="当前案例暂无通知">
      <el-table-column label="事件" min-width="150"><template #default="{row}">{{ events[row.eventType] || row.eventType }}</template></el-table-column>
      <el-table-column label="收件人" min-width="180"><template #default="{row}">{{ row.audience === 'STAFF' ? '售后公共邮箱' : '客户' }} · {{ row.recipient || '待配置' }}<small v-if="row.originalRecipient && row.originalRecipient !== row.recipient">原地址：{{ row.originalRecipient }}</small></template></el-table-column>
      <el-table-column label="状态 / 最近原因" min-width="200"><template #default="{row}">{{ states[row.status as keyof typeof states] }}<small v-if="row.lastError">{{ errors[row.lastError] || row.lastError }}</small></template></el-table-column>
      <el-table-column prop="attemptCount" label="本轮尝试" width="90" />
      <el-table-column label="创建时间" min-width="165"><template #default="{row}">{{ time(row.createdAt) }}</template></el-table-column>
      <el-table-column label="发送 / 下次重试" min-width="165"><template #default="{row}">{{ row.sentAt ? time(row.sentAt) : row.status === 'RETRY' ? time(row.nextAttemptAt) : '—' }}</template></el-table-column>
      <el-table-column label="操作" width="105"><template #default="{row}"><el-button v-if="['FAILED','BLOCKED','RETRY'].includes(row.status)" link type="primary" :disabled="saving" @click="open(row)">检查并重试</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="20" :total="result?.notifications.total || 0" layout="total,prev,pager,next" @current-change="load" />
    <el-dialog v-model="visible" title="检查通知并重新排队" width="min(560px,95vw)" append-to-body :close-on-click-modal="false">
      <el-alert v-if="selected?.lastError === 'DELIVERY_RESULT_UNKNOWN'" :title="errors.DELIVERY_RESULT_UNKNOWN" type="warning" :closable="false" />
      <el-form label-position="top" @submit.prevent="retry">
        <el-form-item v-if="selected?.audience === 'CUSTOMER'" label="客户收件Email"><el-input v-model="form.recipient" maxlength="254" placeholder="核对客户确认的收件地址" /></el-form-item>
        <p v-else>内部通知使用服务器配置的售后公共邮箱；请先确认配置已更正。</p>
        <el-form-item label="重试或更正原因" required><el-input v-model="form.reason" type="textarea" maxlength="1000" :rows="3" /></el-form-item>
        <el-button type="primary" native-type="submit" :loading="saving">重新排队</el-button>
      </el-form>
    </el-dialog>
  </section>
</template>
<style scoped>
section{display:grid;gap:14px}p{font-size:13px;color:#6d7d8b;line-height:1.6;margin:0}small{display:block;font-size:12px;color:#7b8794;margin-top:5px;overflow-wrap:anywhere}.el-pagination{justify-content:flex-end}
</style>
