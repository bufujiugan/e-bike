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
onMounted(async()=>{try{await refreshWorkOrders();taskId.value=localStorage.getItem(taskStorageKey.value)??'';if(taskId.value)await resume()}catch(e){error(e)}})
</script>

<template>
  <section class="production-station">
    <van-notice-bar text="四码任意顺序扫描，集齐后自动生成聚合码。部件需先登记，新车架请选择车架类型。" wrapable />
    <div class="station-card">
      <h3>当前生产工单</h3>
      <van-field v-model="workOrderKeyword" label="查找工单" placeholder="工单号、SKU 或批次" clearable @keyup.enter="run(refreshWorkOrders)" />
      <label>生产工单<select v-model="workOrderId" aria-label="生产工单" :disabled="!!task" @change="selectWorkOrder"><option :value="undefined" disabled>请选择已下达工单</option><option v-for="row in selectableWorkOrders" :key="row.id" :value="row.id">{{ row.workOrderNo }} · {{ row.skuCode }} · {{ row.batchNo }}</option></select></label>
      <p v-if="!busy && workOrderKeyword.trim() && !workOrders.length" class="task-number">未找到匹配的已下达工单，请核对工单号、SKU 或批次。</p>
      <template v-if="selectedWorkOrder">
        <p>{{ selectedWorkOrder.skuCode }} · {{ selectedWorkOrder.productName }}</p>
        <p class="task-number">批次：{{ selectedWorkOrder.batchNo }}</p>
        <van-tag class="work-order-progress" type="primary">已完成 {{ selectedWorkOrder.completedQuantity }}/{{ selectedWorkOrder.plannedQuantity }} · 作业中 {{ selectedWorkOrder.inProgressQuantity }}</van-tag>
      </template>
      <van-button class="refresh-work-orders" size="small" :disabled="busy" @click="run(refreshWorkOrders)">搜索 / 刷新</van-button>
    </div>
    <div class="station-card">
      <h3>{{ task ? '当前单车' : '扫描下一台车' }}</h3>
      <p v-if="task" class="task-number">单车任务：{{ task.id }} · {{ task.scans.length }}/4</p>
      <van-cell v-if="task" v-for="(name,type) in labels" :key="type" :title="name" :value="task.scans.find(s=>s.codeType===type)?.code || '待扫描'" />
      <label>编码类型<select v-model="expectedType" aria-label="编码类型" :disabled="!workOrderId"><option value="">自动识别</option><option v-for="(name,type) in labels" :key="type" :value="type">{{name}}</option></select></label>
      <van-field ref="scanInput" v-model="scanCode" label="扫描编码" placeholder="首码自动建单车记录，四码可任意顺序" :disabled="busy || !workOrderId" autofocus @keyup.enter="scan" />
      <van-button block type="primary" :loading="busy" :disabled="!workOrderId || !scanCode.trim()" @click="scan">确认本次扫描</van-button>
      <template v-if="task">
        <van-field v-model="reason" label="取消原因" placeholder="更换已扫描编码时，取消当前单车" maxlength="500" />
        <van-button size="small" type="danger" plain :disabled="busy" @click="cancel">取消当前单车并释放占用</van-button>
      </template>
    </div>
    <div class="station-card">
      <van-field v-model="taskId" label="单车任务" placeholder="跨设备交接时输入任务号" />
      <van-button size="small" :disabled="busy || !taskId.trim()" @click="resume">恢复 / 刷新单车进度</van-button>
    </div>
    <div v-if="lastCompleted" class="station-card">
      <h3>上一台已完成</h3>
      <div class="completed-card">
        <strong>总码已生成</strong><p class="task-number">{{ lastCompleted.qrCode }}</p>
        <p>自动打印终端连接后会领取任务；也可使用下方人工打印。</p>
        <label>打印任务<select v-model="printId" :disabled="busy" @change="run(loadLabel)"><option v-for="job in printJobs" :key="job.id" :value="job.id">{{ printStates[job.status] ?? job.status }} · {{ job.reason || job.id }}</option></select></label>
        <p v-if="selectedPrint">{{ printStates[selectedPrint.status] }} · {{ selectedPrint.message || '等待处理' }}</p>
        <van-button size="small" :disabled="busy" @click="run(() => preview())">刷新打印状态</van-button>
        <iframe v-if="labelHtml" ref="labelFrame" :srcdoc="labelHtml" title="整车外箱标签" class="label-frame" sandbox="allow-same-origin allow-modals" @load="labelReady = true" />
        <van-button type="primary" :disabled="busy || !labelReady || selectedPrint?.status !== 'PENDING'" @click="printBrowser">人工打印所选任务</van-button>
        <van-button size="small" :disabled="busy || !canReportPrint" @click="printResult('SUBMITTED')">确认已提交打印</van-button>
        <van-button size="small" :disabled="busy || !canReportPrint" @click="printResult('FAILED')">确认打印失败</van-button>
        <van-button size="small" :disabled="busy || !canReportPrint" @click="printResult('UNKNOWN')">结果无法确认</van-button>
        <template v-if="selectedPrint && ['SUBMITTED','FAILED','UNKNOWN'].includes(selectedPrint.status)">
          <van-field v-model="reprintReason" label="补打原因" placeholder="核对实物后填写，避免重复出纸" maxlength="500" />
          <van-button size="small" :disabled="busy || !reprintReason.trim()" @click="reprint">创建补打任务</van-button>
        </template>
      </div>
    </div>
    <van-collapse v-model="issuePanel" accordion>
      <van-collapse-item title="登记生产异常 / 上传照片" name="issue">
        <van-field v-model="issueCode" label="异常编码" placeholder="整车聚合码或部件码" maxlength="200" />
        <van-field v-model="issueReason" label="异常原因" type="textarea" maxlength="500" />
        <van-button block type="danger" plain :loading="busy" :disabled="imageUploading" @click="reportIssue">登记异常并隔离</van-button>
        <p v-if="issueId">照片归属：{{ savedIssueCode }} · 异常 {{ issueId }}</p>
        <ProductionImageUpload v-if="issueId" :key="issueId" :endpoint="`/api/production/issues/${issueId}/images`" images-only @busy="imageUploading = $event" />
      </van-collapse-item>
    </van-collapse>
    <van-notice-bar v-if="message" :text="message" wrapable :color="failed ? '#c62828' : '#16794b'" :background="failed ? '#fff0f0' : '#eaf8ef'" />
  </section>
</template>

<style scoped>
select{display:block;width:100%;padding:12px;margin:8px 0;border:1px solid #ccd6e3;border-radius:8px;background:white;font:inherit}label{font-size:14px;color:#46566d}
.production-station{display:grid;gap:14px}.station-card{background:white;padding:14px;border:1px solid #e5eaf0;border-radius:12px}.station-card h3{margin:0 0 10px}.task-number{font-size:12px;overflow-wrap:anywhere;color:#53627a}.station-card .van-button{margin-top:10px}.work-order-progress{display:flex;width:max-content;max-width:100%;white-space:normal;line-height:20px}.station-card .refresh-work-orders{display:block}.completed-card{padding-top:14px}.completed-card p{font-size:13px}.label-frame{width:100%;height:440px;border:1px solid #dce3ed;margin:12px 0}.van-collapse input{max-width:100%;margin:12px 0}
</style>
