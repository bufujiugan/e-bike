<script setup lang="ts">
import { ref, computed, onMounted, nextTick } from 'vue'
import api from '../api'
import ProductionImageUpload from './ProductionImageUpload.vue'
import { showConfirmDialog } from 'vant'

type Scan = { codeType: string; code: string; operatorName: string }
type WorkOrder = { id: number; workOrderNo: string; status: string; skuCode: string; productName: string; batchNo: string; plannedQuantity: number; completedQuantity: number; inProgressQuantity: number }
type Task = { id: string; workOrderId: number; status: string; skuId: number; skuCode: string; productName: string; batchNo?: string; frameSn?: string; qrCode?: string; scans: Scan[]; workOrder?: WorkOrder }
const props = defineProps<{ username: string }>()
const workOrders = ref<WorkOrder[]>([]), workOrderId = ref<number>(), task = ref<Task>(), taskId = ref(''), lastCompleted = ref<Task>()
const workOrderKeyword = ref(''), preservedWorkOrder = ref<WorkOrder>()
const scanCode = ref(''), expectedType = ref(''), busy = ref(false), message = ref(''), failed = ref(false)
const scanInput = ref<{focus:()=>void}>(), labelHtml = ref(''), labelFrame = ref<HTMLIFrameElement>()
const reason = ref(''), issueCode = ref(''), issueReason = ref(''), issueId = ref('')
const issuePanel = ref('')
const savedIssueCode = ref(''), imageUploading = ref(false)
const taskStorageKey = computed(() => `bike_production_task:${props.username}`)
const workOrderStorageKey = computed(() => `bike_production_work_order:${props.username}`)
const labels: Record<string,string> = { FRAME:'车架', MOTOR:'电机', BATTERY:'电池', DISPLAY:'仪表' }
const selectedWorkOrder = computed(() => workOrders.value.find(row => row.id === workOrderId.value) ?? task.value?.workOrder ?? lastCompleted.value?.workOrder ?? (preservedWorkOrder.value?.id === workOrderId.value ? preservedWorkOrder.value : undefined))
const workOrderProgress = computed(() => {
  const workOrder = selectedWorkOrder.value
  if (!workOrder || workOrder.plannedQuantity <= 0) return 0
  return Math.min(100, Math.round(workOrder.completedQuantity / workOrder.plannedQuantity * 100))
})
const scanProgress = computed(() => task.value?.scans.length ?? 0)
const scanFeedbackClass = computed(() => busy.value ? 'is-loading' : failed.value ? 'is-error' : message.value ? 'is-success' : '')
const selectableWorkOrders = computed(() => {
  const selected = selectedWorkOrder.value
  return selected && !workOrders.value.some(row => row.id === selected.id) ? [selected, ...workOrders.value] : workOrders.value
})
type PrintJob = { id: string; status: string; claimedBy?: string; reason?: string; message?: string }
const printJobs = ref<PrintJob[]>([]), printId = ref(''), labelReady = ref(false), reprintReason = ref('')
const selectedPrint = computed(() => printJobs.value.find(job => job.id === printId.value))
const printStates: Record<string,string> = { PENDING:'待打印', PROCESSING:'已领取，等待回报', SUBMITTED:'已提交打印队列', FAILED:'打印失败', UNKNOWN:'结果待核对' }
const canReportPrint = computed(() => selectedPrint.value?.status === 'PROCESSING' && selectedPrint.value.claimedBy === props.username)
function error(e: unknown) { failed.value=true;message.value=api.isAxiosError(e)?e.response?.data?.message??'请求失败，请重试':String(e) }
async function run(action:()=>Promise<void>) { if(busy.value)return;busy.value=true;failed.value=false;try{await action()}catch(e){error(e)}finally{busy.value=false;await nextTick();scanInput.value?.focus()} }
async function refreshWorkOrders() {
  const current = selectedWorkOrder.value
  workOrders.value = (await api.get('/api/production/work-orders', { params: { status:'RELEASED', page:1, keyword:workOrderKeyword.value.trim() || undefined } })).data.data.records
  if (current?.id === workOrderId.value) preservedWorkOrder.value = current
  const saved = Number(localStorage.getItem(workOrderStorageKey.value))
  if (!workOrderId.value && workOrders.value.some(row => row.id === saved)) workOrderId.value = saved
  if (!workOrderId.value && workOrders.value.length === 1) workOrderId.value = workOrders.value[0]?.id
  const matched = workOrders.value.find(row => row.id === workOrderId.value)
  if (matched) preservedWorkOrder.value = matched
  if (workOrderId.value) localStorage.setItem(workOrderStorageKey.value, String(workOrderId.value))
}
function selectWorkOrder() {
  preservedWorkOrder.value = workOrders.value.find(row => row.id === workOrderId.value)
  if (workOrderId.value) localStorage.setItem(workOrderStorageKey.value, String(workOrderId.value))
  task.value=undefined;taskId.value='';lastCompleted.value=undefined;localStorage.removeItem(taskStorageKey.value)
  printId.value='';printJobs.value=[];labelHtml.value='';message.value='生产工单已选择，扫描第一个码后自动建立单车记录'
}
async function loadLabel() {
  labelReady.value = false; labelHtml.value = ''
  if (printId.value) labelHtml.value = (await api.get(`/api/production/prints/${encodeURIComponent(printId.value)}/label`, { responseType:'text' })).data
}
async function preview(targetTaskId = lastCompleted.value?.id ?? task.value?.id) {
  if (!targetTaskId) return
  printJobs.value = (await api.get(`/api/production/tasks/${targetTaskId}/prints`)).data.data
  if (!printJobs.value.some(job => job.id === printId.value)) printId.value = (printJobs.value.find(job => job.status === 'PENDING') ?? printJobs.value[0])?.id ?? ''
  await loadLabel()
}
function accept(value: Task) {
  task.value=value;taskId.value=value.id;workOrderId.value=value.workOrderId
  localStorage.setItem(taskStorageKey.value,value.id);localStorage.setItem(workOrderStorageKey.value,String(value.workOrderId))
}
async function resume() { await run(async()=>{const restored=(await api.get(`/api/production/tasks/${encodeURIComponent(taskId.value.trim())}`)).data.data as Task;if(!restored.workOrderId)throw new Error('该历史任务未关联生产工单');accept(restored);message.value='单车作业进度已恢复';labelHtml.value=''}) }
async function scan() {
  if(!workOrderId.value||!scanCode.value.trim())return
  await run(async()=>{
    const result=(await api.post(`/api/production/work-orders/${workOrderId.value}/scan`,{taskId:task.value?.id??null,code:scanCode.value.trim(),expectedType:expectedType.value||null})).data.data as Task
    accept(result);scanCode.value='';expectedType.value=''
    if(result.status==='COMPLETED'){
      lastCompleted.value=result;await preview(result.id);task.value=undefined;taskId.value='';localStorage.removeItem(taskStorageKey.value)
      if(result.workOrder?.status==='COMPLETED'){workOrderId.value=undefined;localStorage.removeItem(workOrderStorageKey.value)}
      await refreshWorkOrders();message.value='本车四码绑定完成，总码和打印任务已生成；可以直接扫描下一台'
    }else message.value=`扫码成功，当前单车已完成 ${result.scans.length}/4`
  })
}
async function cancel() { if(!task.value||!reason.value.trim()) {message.value='请填写取消原因';failed.value=true;return}try{await showConfirmDialog({title:'取消当前单车作业',message:'已扫描记录将保留，部件占用会释放，工单计划名额可重新使用。'})}catch(action){if(action!=='cancel')error(action);return}await run(async()=>{await api.post(`/api/production/tasks/${task.value!.id}/cancel`,{reason:reason.value});task.value=undefined;taskId.value='';localStorage.removeItem(taskStorageKey.value);message.value='本车任务已取消，可以扫描下一台';reason.value='';await refreshWorkOrders()}) }
async function printBrowser() { await run(async()=>{
  if (!selectedPrint.value || !labelReady.value || !labelFrame.value?.contentWindow) throw new Error('请选择任务并等待标签加载完成')
  const job = (await api.post(`/api/production/prints/${printId.value}/claim`,{terminal:'browser-manual'})).data.data
  printJobs.value = printJobs.value.map(item => item.id === job.id ? job : item)
  labelFrame.value.contentWindow.print(); message.value='已打开打印对话框，请核对实物并回报结果'
}) }
async function printResult(status:string) { await run(async()=>{
  if (!canReportPrint.value) throw new Error('只能回报当前账号领取且尚未超时的任务')
  await api.post(`/api/production/prints/${printId.value}/result`,{status,message:status==='SUBMITTED'?'操作员确认已提交浏览器打印':status==='UNKNOWN'?'实物结果无法确认，等待核对':'操作员确认未成功打印'})
  await preview(); message.value='打印结果已记录'
}) }
async function reprint() { await run(async()=>{
  if (!selectedPrint.value || !reprintReason.value.trim()) throw new Error('请核对实物并填写补打原因')
  const job = (await api.post(`/api/production/prints/${printId.value}/reprint`,{reason:reprintReason.value.trim()})).data.data
  printId.value=job.id; await preview(); reprintReason.value=''; message.value='补打任务已创建并选中，可以人工打印或等待自动终端领取'
}) }
async function reportIssue(){if(imageUploading.value)return;await run(async()=>{const code=issueCode.value;const result=(await api.post('/api/production/issues',{code,reason:issueReason.value})).data.data;issueId.value=result.id;savedIssueCode.value=code;message.value='异常已登记，相关车辆/部件已隔离'})}
function focusScanInput() { void nextTick(() => scanInput.value?.focus()) }
function openIssuePanel() { issuePanel.value = issuePanel.value === 'issue' ? '' : 'issue' }
async function copyAggregateCode() {
  const value = lastCompleted.value?.qrCode
  if (!value) return
  try {
    if (navigator.clipboard && window.isSecureContext) await navigator.clipboard.writeText(value)
    else {
      const textarea = document.createElement('textarea')
      textarea.value = value; textarea.style.position = 'fixed'; textarea.style.opacity = '0'
      document.body.appendChild(textarea); textarea.select()
      if (!document.execCommand('copy')) throw new Error('copy failed')
      document.body.removeChild(textarea)
    }
    failed.value = false; message.value = '聚合码已复制'
  } catch {
    failed.value = true; message.value = '聚合码复制失败，请长按编号手动复制'
  }
}
onMounted(async()=>{try{await refreshWorkOrders();taskId.value=localStorage.getItem(taskStorageKey.value)??'';if(taskId.value)await resume()}catch(e){error(e)}})
</script>

<template>
  <section class="production-station">
    <section class="production-alert" aria-label="生产扫码提示">
      <span class="alert-icon"><van-icon name="warning-o" /></span>
      <p>四码任意顺序扫描，集齐后自动生成聚合码。部件需先登记，新车架请选择车架类型。</p>
    </section>

    <section class="station-card work-order-card">
      <header class="card-heading">
        <span class="heading-icon is-blue"><van-icon name="records-o" /></span>
        <div><h2>当前生产工单</h2><p>选择已下达工单并核对生产信息</p></div>
        <span class="status-pill" :class="selectedWorkOrder ? 'is-active' : ''">{{ selectedWorkOrder ? '生产作业中' : '等待选择' }}</span>
      </header>

      <div class="work-order-search">
        <van-field v-model="workOrderKeyword" aria-label="查找生产工单" placeholder="工单号、SKU 或批次查询" clearable @keyup.enter="run(refreshWorkOrders)">
          <template #left-icon><van-icon name="search" /></template>
        </van-field>
        <van-button :loading="busy" :disabled="busy" @click="run(refreshWorkOrders)"><van-icon name="replay" />查询</van-button>
      </div>

      <label class="control-label"><span><van-icon name="todo-list-o" />生产工单</span>
        <select v-model="workOrderId" aria-label="生产工单" :disabled="!!task" @change="selectWorkOrder">
          <option :value="undefined" disabled>请选择已下达工单</option>
          <option v-for="row in selectableWorkOrders" :key="row.id" :value="row.id">{{ row.workOrderNo }} · {{ row.skuCode }} · {{ row.batchNo }}</option>
        </select>
      </label>
      <p v-if="!busy && workOrderKeyword.trim() && !workOrders.length" class="empty-hint">未找到匹配的已下达工单，请核对工单号、SKU 或批次。</p>

      <div v-if="selectedWorkOrder" class="work-order-details">
        <div class="detail-row"><span><van-icon name="cluster-o" />SKU 信息</span><strong>{{ selectedWorkOrder.skuCode }} · {{ selectedWorkOrder.productName }}</strong></div>
        <div class="detail-row"><span><van-icon name="label-o" />批次</span><strong>{{ selectedWorkOrder.batchNo }}</strong></div>
        <div class="progress-block">
          <div class="progress-meta"><span><van-icon name="chart-trending-o" />完成进度</span><strong>{{ selectedWorkOrder.completedQuantity }}/{{ selectedWorkOrder.plannedQuantity }}</strong></div>
          <div class="progress-line"><i :style="{ width: `${workOrderProgress}%` }" /></div>
          <div class="progress-footer"><span>{{ workOrderProgress }}%</span><span class="in-progress-count">作业中 {{ selectedWorkOrder.inProgressQuantity }}</span></div>
        </div>
      </div>
      <div v-else class="work-order-empty"><van-icon name="orders-o" /><span>请选择生产工单后开始扫描</span></div>
    </section>

    <section class="station-card scan-card" :class="{ 'has-task': task }">
      <header class="scan-heading">
        <span class="heading-icon is-green"><van-icon name="scan" /></span>
        <div><h2>{{ task ? '继续扫描当前单车' : '扫描下一台车' }}</h2><p>请扫描车架码 / 部件码，系统将自动识别</p></div>
        <span v-if="task" class="scan-count">{{ scanProgress }}/4</span>
      </header>

      <div v-if="task" class="current-task-summary">
        <p><span>单车任务</span><strong>{{ task.id }}</strong></p>
        <div class="code-progress-grid">
          <div v-for="(name,type) in labels" :key="type" :class="{ 'is-scanned': task.scans.some(scan => scan.codeType === type) }">
            <van-icon :name="task.scans.some(scan => scan.codeType === type) ? 'checked' : 'underway-o'" />
            <span>{{ name }}</span>
            <strong>{{ task.scans.find(scan=>scan.codeType===type)?.code || '待扫描' }}</strong>
          </div>
        </div>
      </div>

      <div class="scan-controls">
        <label class="control-label"><span><van-icon name="description-o" />编码类型</span>
          <select v-model="expectedType" aria-label="编码类型" :disabled="!workOrderId">
            <option value="">自动识别</option>
            <option v-for="(name,type) in labels" :key="type" :value="type">{{name}}</option>
          </select>
        </label>
        <label class="control-label"><span><van-icon name="bars" />扫描编码</span>
          <van-field ref="scanInput" v-model="scanCode" aria-label="扫描编码" placeholder="首码自动建单车记录，四码可任意顺序" :disabled="busy || !workOrderId" autofocus clearable @keyup.enter="scan">
            <template #right-icon><button class="scan-focus-button" type="button" aria-label="聚焦扫描输入框" :disabled="busy || !workOrderId" @click="focusScanInput"><van-icon name="scan" /></button></template>
          </van-field>
        </label>
      </div>

      <div v-if="busy || message" class="scan-feedback" :class="scanFeedbackClass" role="status">
        <van-icon :name="busy ? 'replay' : failed ? 'close' : 'checked'" :class="{ rotating: busy }" />
        <span>{{ busy ? '正在校验扫描编码，请稍候…' : message }}</span>
      </div>

      <van-button class="scan-submit" block type="primary" :loading="busy" :disabled="!workOrderId || !scanCode.trim()" @click="scan"><van-icon name="scan" />确认本次扫描</van-button>

      <div v-if="task" class="cancel-task-panel">
        <van-field v-model="reason" label="取消原因" placeholder="更换已扫描编码时，填写原因后取消当前单车" maxlength="500" />
        <van-button type="danger" plain :disabled="busy" @click="cancel">取消当前单车并释放占用</van-button>
      </div>
    </section>

    <section class="station-card resume-card">
      <header class="compact-heading"><div><van-icon name="passed" /><h2>单车任务</h2></div><p>跨设备交接时输入任务号</p></header>
      <div class="resume-controls">
        <van-field v-model="taskId" aria-label="单车任务号" placeholder="请输入任务号" clearable />
        <van-button :disabled="busy || !taskId.trim()" @click="resume"><van-icon name="replay" />恢复 / 刷新进度</van-button>
      </div>
    </section>

    <section class="station-card production-actions-card">
      <div class="production-actions">
        <button type="button" @click="openIssuePanel"><span class="action-icon is-warning"><van-icon name="warning-o" /></span><strong>登记生产异常</strong><van-icon name="arrow" /></button>
        <button type="button" @click="openIssuePanel"><span class="action-icon is-photo"><van-icon name="photograph" /></span><strong>上传照片</strong><van-icon name="arrow" /></button>
      </div>
      <van-collapse v-if="issuePanel" v-model="issuePanel" accordion>
        <van-collapse-item title="生产异常与现场照片" name="issue">
          <van-field v-model="issueCode" label="异常编码" placeholder="整车聚合码或部件码" maxlength="200" />
          <van-field v-model="issueReason" label="异常原因" type="textarea" placeholder="请描述异常现象和处理要求" maxlength="500" autosize />
          <van-button block type="danger" plain :loading="busy" :disabled="imageUploading" @click="reportIssue">登记异常并隔离</van-button>
          <p v-if="issueId" class="issue-owner">照片归属：{{ savedIssueCode }} · 异常 {{ issueId }}</p>
          <ProductionImageUpload v-if="issueId" :key="issueId" :endpoint="`/api/production/issues/${issueId}/images`" images-only @busy="imageUploading = $event" />
        </van-collapse-item>
      </van-collapse>
    </section>

    <section v-if="lastCompleted" class="station-card completed-card">
      <div class="completion-summary">
        <span class="completion-icon"><van-icon name="checked" /></span>
        <div><p>上一台单车已完成，聚合码已生成</p><strong>{{ lastCompleted.qrCode }}</strong></div>
        <button type="button" aria-label="复制聚合码" @click="copyAggregateCode"><van-icon name="notes-o" /></button>
      </div>
      <p class="print-tip">自动打印终端连接后会领取任务；也可展开使用人工打印。</p>
      <van-collapse accordion>
        <van-collapse-item title="打印、结果回报与补打" name="print">
          <label class="control-label"><span><van-icon name="printer" />打印任务</span>
            <select v-model="printId" :disabled="busy" @change="run(loadLabel)"><option v-for="job in printJobs" :key="job.id" :value="job.id">{{ printStates[job.status] ?? job.status }} · {{ job.reason || job.id }}</option></select>
          </label>
          <p v-if="selectedPrint" class="print-state">{{ printStates[selectedPrint.status] }} · {{ selectedPrint.message || '等待处理' }}</p>
          <div class="print-actions"><van-button size="small" :disabled="busy" @click="run(() => preview())">刷新打印状态</van-button><van-button type="primary" :disabled="busy || !labelReady || selectedPrint?.status !== 'PENDING'" @click="printBrowser">人工打印所选任务</van-button></div>
          <iframe v-if="labelHtml" ref="labelFrame" :srcdoc="labelHtml" title="整车外箱标签" class="label-frame" sandbox="allow-same-origin allow-modals" @load="labelReady = true" />
          <div class="print-result-actions"><van-button size="small" :disabled="busy || !canReportPrint" @click="printResult('SUBMITTED')">确认已提交打印</van-button><van-button size="small" :disabled="busy || !canReportPrint" @click="printResult('FAILED')">确认打印失败</van-button><van-button size="small" :disabled="busy || !canReportPrint" @click="printResult('UNKNOWN')">结果无法确认</van-button></div>
          <template v-if="selectedPrint && ['SUBMITTED','FAILED','UNKNOWN'].includes(selectedPrint.status)">
            <van-field v-model="reprintReason" label="补打原因" placeholder="核对实物后填写，避免重复出纸" maxlength="500" />
            <van-button size="small" :disabled="busy || !reprintReason.trim()" @click="reprint">创建补打任务</van-button>
          </template>
        </van-collapse-item>
      </van-collapse>
    </section>

    <div v-if="message" class="result-banner" :class="failed ? 'is-error' : 'is-success'" role="status"><van-icon :name="failed ? 'close' : 'checked'" /><span>{{ message }}</span></div>
  </section>
</template>

<style scoped>
.production-station{--station-blue:#0878f9;--station-blue-dark:#075dcc;--station-ink:#102a4d;--station-muted:#6f819b;--station-border:#dbe7f5;display:grid;min-width:0;gap:12px}.production-station *{min-width:0}.station-card{overflow:hidden;border:1px solid var(--station-border);border-radius:8px;background:#fff;box-shadow:0 8px 22px rgb(38 88 145 / 7%)}.production-alert{display:flex;align-items:center;gap:12px;padding:14px 16px;color:#b64e08;border:1px solid #f5aa32;border-radius:8px;background:#fff8e9}.production-alert p{margin:0;font-size:14px;font-weight:600;line-height:1.65;overflow-wrap:anywhere}.alert-icon{display:grid;flex:0 0 40px;width:40px;height:40px;place-items:center;color:#fff;font-size:24px;border-radius:8px;background:#ff9418}.card-heading,.scan-heading{display:flex;align-items:center;gap:12px;padding:16px 16px 14px;border-bottom:1px solid #e9f0f8;background:#f8fbff}.card-heading h2,.scan-heading h2,.compact-heading h2{margin:0;color:var(--station-ink);font-size:19px;line-height:1.3}.card-heading p,.scan-heading p{margin:4px 0 0;color:var(--station-muted);font-size:12px;line-height:1.4}.heading-icon{display:grid;flex:0 0 44px;width:44px;height:44px;place-items:center;color:#fff;font-size:23px;border-radius:8px}.heading-icon.is-blue{background:var(--station-blue);box-shadow:0 6px 14px rgb(8 120 249 / 22%)}.heading-icon.is-green{background:#18b46b;box-shadow:0 6px 14px rgb(24 180 107 / 22%)}.status-pill{margin-left:auto;padding:6px 9px;color:#7b8ba0;font-size:11px;font-weight:700;white-space:nowrap;border-radius:999px;background:#edf2f7}.status-pill.is-active{color:#0873e7;background:#e9f4ff}.work-order-search{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:8px;padding:14px 14px 0}.work-order-search .van-cell,.resume-controls .van-cell,.scan-controls .van-cell{align-items:center;padding:0 12px;border:1px solid #cbdbea;border-radius:8px;background:#fff}.work-order-search .van-cell{height:46px}.work-order-search .van-button{height:46px;margin:0;padding:0 14px;color:var(--station-blue);font-weight:700;border-color:#bcd5ee}.work-order-search .van-button .van-icon,.resume-controls .van-button .van-icon{margin-right:6px}.control-label{display:block;padding:14px 14px 0;color:#334d6f;font-size:13px;font-weight:700}.control-label>span{display:flex;align-items:center;gap:7px;margin-bottom:7px}.control-label>span .van-icon{color:#547398;font-size:17px}select{display:block;width:100%;height:48px;padding:0 38px 0 12px;overflow:hidden;color:#162d4d;font:inherit;font-weight:500;text-overflow:ellipsis;white-space:nowrap;border:1px solid #cbdbea;border-radius:8px;background:#fff}select:disabled{color:#9caabe;background:#f4f7fa}.empty-hint{margin:10px 14px 0;padding:10px 12px;color:#a5541d;font-size:12px;line-height:1.5;border-radius:6px;background:#fff6e9}.work-order-details{padding:10px 14px 16px}.detail-row{display:grid;grid-template-columns:92px minmax(0,1fr);align-items:start;gap:8px;padding:9px 0}.detail-row>span,.progress-meta>span{display:flex;align-items:center;gap:7px;color:#617895;font-size:13px}.detail-row>strong{color:#193658;font-size:14px;line-height:1.5;overflow-wrap:anywhere}.progress-block{margin-top:4px;padding-top:10px;border-top:1px solid #edf2f7}.progress-meta,.progress-footer{display:flex;align-items:center;justify-content:space-between;gap:10px}.progress-meta strong{color:#163453;font-size:15px}.progress-line{height:9px;margin:10px 0 7px;overflow:hidden;border-radius:999px;background:#e6edf5}.progress-line i{display:block;height:100%;border-radius:inherit;background:linear-gradient(90deg,#0a83ff,#23a8ee)}.progress-footer{color:#71839a;font-size:11px}.in-progress-count{padding:4px 8px;color:#0873e7;font-weight:700;border-radius:5px;background:#eaf4ff}.work-order-empty{display:flex;align-items:center;justify-content:center;gap:8px;margin:12px 14px 16px;padding:18px;color:#8b9bb0;font-size:13px;border:1px dashed #cfddeb;border-radius:8px;background:#f8fbfe}.work-order-empty .van-icon{font-size:22px}.scan-card{border-color:#58d48b;background:#fbfffd}.scan-heading{border-color:#d9f4e4;background:#effcf5}.scan-heading>div:nth-child(2){flex:1}.scan-heading h2{font-size:21px}.scan-heading h2::first-letter{color:var(--station-ink)}.scan-count{display:grid;flex:0 0 42px;width:42px;height:42px;place-items:center;color:#108c54;font-size:13px;font-weight:800;border-radius:50%;background:#dff7e9}.current-task-summary{margin:12px 14px 0;padding:12px;border:1px solid #d6eee1;border-radius:8px;background:#fff}.current-task-summary>p{display:grid;grid-template-columns:76px minmax(0,1fr);gap:8px;margin:0 0 10px;color:#6f8199;font-size:12px}.current-task-summary>p strong{color:#234160;overflow-wrap:anywhere}.code-progress-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:7px}.code-progress-grid>div{display:grid;grid-template-columns:18px minmax(0,1fr);gap:2px 6px;padding:9px;color:#8697aa;border:1px solid #e3ebf2;border-radius:7px;background:#f7f9fb}.code-progress-grid>div.is-scanned{color:#168a56;border-color:#bfe9d1;background:#eefaf3}.code-progress-grid .van-icon{grid-row:1/3;align-self:center}.code-progress-grid span{font-size:11px}.code-progress-grid strong{font-size:11px;font-weight:600;overflow-wrap:anywhere}.scan-controls{display:grid;gap:0;padding-bottom:14px}.scan-controls .control-label{padding-top:12px}.scan-controls .van-cell{height:52px;padding-right:6px;border-color:#65aef7;box-shadow:0 0 0 3px rgb(51 148 245 / 8%)}.scan-focus-button{display:grid;width:40px;height:40px;padding:0;place-items:center;color:var(--station-blue);font-size:25px;border:0;border-left:1px solid #d7e6f6;background:#f6fbff}.scan-focus-button:disabled{color:#aebcca}.scan-feedback{display:flex;align-items:flex-start;gap:8px;margin:0 14px 12px;padding:10px 12px;color:#176d49;font-size:12px;line-height:1.5;border-radius:7px;background:#eaf8f0}.scan-feedback.is-error{color:#b53131;background:#fff0f0}.scan-feedback.is-loading{color:#1669b7;background:#eaf4ff}.scan-feedback .van-icon{flex:0 0 auto;margin-top:2px}.rotating{animation:spin .9s linear infinite}.scan-submit{width:calc(100% - 28px);height:54px;margin:0 14px 16px!important;font-size:17px;font-weight:800;border:0;border-radius:8px;background:linear-gradient(100deg,#1b8cff,#0570ed)}.scan-submit .van-icon{margin-right:8px;font-size:21px}.cancel-task-panel{display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:end;gap:8px;padding:12px 14px;border-top:1px solid #edf2f4;background:#fff}.cancel-task-panel .van-cell{padding:5px 8px}.cancel-task-panel .van-button{height:42px;margin:0}.resume-card{padding:14px}.compact-heading{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:12px}.compact-heading>div{display:flex;align-items:center;gap:8px}.compact-heading>div>.van-icon{color:#315d87;font-size:22px}.compact-heading p{margin:0;color:#7e8fa5;font-size:11px;text-align:right}.resume-controls{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:8px}.resume-controls .van-cell{height:46px}.resume-controls .van-button{height:46px;margin:0;padding:0 12px;font-size:12px;font-weight:700}.production-actions-card{padding:10px}.production-actions{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.production-actions button{display:grid;grid-template-columns:36px minmax(0,1fr) 16px;align-items:center;gap:8px;min-height:56px;padding:8px 10px;color:#1c3555;text-align:left;border:1px solid #dbe5f0;border-radius:8px;background:#fff}.production-actions strong{font-size:13px}.action-icon{display:grid;width:36px;height:36px;place-items:center;font-size:20px;border-radius:7px}.action-icon.is-warning{color:#d96a00;background:#fff3df}.action-icon.is-photo{color:#0878f9;background:#eaf4ff}.production-actions-card :deep(.van-collapse){margin-top:8px}.production-actions-card :deep(.van-collapse-item__content){padding:10px 0 0}.issue-owner{margin:10px 12px;color:#61748d;font-size:12px;overflow-wrap:anywhere}.completed-card{border-color:#bfe7d2;background:#f5fcf8}.completion-summary{display:grid;grid-template-columns:42px minmax(0,1fr) 40px;align-items:center;gap:10px;padding:14px}.completion-icon{display:grid;width:42px;height:42px;place-items:center;color:#fff;font-size:24px;border-radius:50%;background:#18ad68}.completion-summary p{margin:0 0 4px;color:#168455;font-size:12px}.completion-summary strong{display:block;color:#126d47;font-size:13px;line-height:1.5;overflow-wrap:anywhere;user-select:text}.completion-summary button{display:grid;width:40px;height:40px;padding:0;place-items:center;color:#0872d9;font-size:20px;border:1px solid #c8e0f4;border-radius:8px;background:#fff}.print-tip{margin:0;padding:0 14px 10px;color:#708299;font-size:11px}.completed-card :deep(.van-collapse-item__content){padding:12px 14px}.completed-card .control-label{padding:0}.print-state{font-size:12px;overflow-wrap:anywhere}.print-actions,.print-result-actions{display:flex;flex-wrap:wrap;gap:8px}.print-actions .van-button,.print-result-actions .van-button{margin:0}.label-frame{width:100%;height:440px;margin:12px 0;border:1px solid #dce3ed}.result-banner{display:flex;align-items:flex-start;gap:10px;padding:13px 14px;font-size:13px;line-height:1.55;border-radius:8px}.result-banner .van-icon{flex:0 0 auto;margin-top:2px;font-size:18px}.result-banner span{overflow-wrap:anywhere}.result-banner.is-success{color:#147449;border:1px solid #bce6cf;background:#eaf9f1}.result-banner.is-error{color:#b43131;border:1px solid #f0c3c3;background:#fff0f0}@keyframes spin{to{transform:rotate(360deg)}}
@media (max-width:380px){.card-heading,.scan-heading{padding:14px 12px}.heading-icon{flex-basis:40px;width:40px;height:40px}.card-heading h2,.scan-heading h2{font-size:17px}.card-heading p,.scan-heading p{font-size:11px}.status-pill{padding:5px 7px}.work-order-search{grid-template-columns:minmax(0,1fr) 48px;padding-right:12px;padding-left:12px}.work-order-search .van-button{padding:0;font-size:0}.work-order-search .van-button .van-icon{margin:0;font-size:18px}.detail-row{grid-template-columns:78px}.cancel-task-panel,.resume-controls{grid-template-columns:1fr}.cancel-task-panel .van-button,.resume-controls .van-button{width:100%}.production-actions{grid-template-columns:1fr}.compact-heading{align-items:flex-start}.compact-heading p{max-width:128px}.code-progress-grid{grid-template-columns:1fr}.scan-submit{height:52px}}
</style>
