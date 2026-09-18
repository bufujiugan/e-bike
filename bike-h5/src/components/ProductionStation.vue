<script setup lang="ts">
import { ref, computed, onMounted, nextTick } from 'vue'
import api from '../api'
import { showConfirmDialog } from 'vant'

type Scan = { codeType: string; code: string; operatorName: string }
type Task = { id: string; status: string; skuId: number; skuCode: string; productName: string; batchNo?: string; frameSn?: string; qrCode?: string; scans: Scan[] }
const props = defineProps<{ username: string }>()
const skus = ref<any[]>([]), skuId = ref<number>(), batchNo = ref(''), task = ref<Task>(), taskId = ref('')
const scanCode = ref(''), expectedType = ref(''), busy = ref(false), message = ref(''), failed = ref(false)
const scanInput = ref<{focus:()=>void}>(), labelHtml = ref(''), labelFrame = ref<HTMLIFrameElement>()
const reason = ref(''), issueCode = ref(''), issueReason = ref(''), issueId = ref(''), imageFile = ref<File>()
const issuePanel = ref('')
const storageKey = computed(() => `bike_production_task:${props.username}`)
const labels: Record<string,string> = { FRAME:'车架', MOTOR:'电机', BATTERY:'电池', DISPLAY:'仪表' }
const options = computed(()=>skus.value.filter(s=>s.enabled).map(s=>({text:`${s.skuCode} · ${s.productName}`,value:s.id})))
const completed = computed(()=>task.value?.status==='COMPLETED')
function error(e: unknown) { failed.value=true;message.value=api.isAxiosError(e)?e.response?.data?.message??'请求失败，请重试':String(e) }
async function run(action:()=>Promise<void>) { if(busy.value)return;busy.value=true;failed.value=false;try{await action()}catch(e){error(e)}finally{busy.value=false;await nextTick();scanInput.value?.focus()} }
async function preview() { if(!task.value)return;labelHtml.value=(await api.get(`/api/production/prints/FIRST-${task.value.id}/label`,{responseType:'text'})).data }
function accept(value: Task) { task.value=value;taskId.value=value.id;localStorage.setItem(storageKey.value,value.id) }
async function start() { await run(async()=>{
 if(!skuId.value){message.value='请选择生产商品';failed.value=true;return}
 const draftKey=storageKey.value+':draft'
 const saved=localStorage.getItem(draftKey)
 const request=saved?JSON.parse(saved):{requestId:crypto.randomUUID(),skuId:skuId.value,batchNo:batchNo.value}
 localStorage.setItem(draftKey,JSON.stringify(request))
 accept((await api.post('/api/production/tasks',request)).data.data)
 localStorage.removeItem(draftKey);labelHtml.value='';message.value='任务已建立，可按任意顺序扫描四码'
 }) }
async function resume() { await run(async()=>{accept((await api.get(`/api/production/tasks/${encodeURIComponent(taskId.value.trim())}`)).data.data);message.value='作业进度已恢复';labelHtml.value='';if(completed.value)await preview()}) }
async function scan() { if(!task.value||!scanCode.value.trim())return;await run(async()=>{accept((await api.post(`/api/production/tasks/${task.value!.id}/scan`,{code:scanCode.value.trim(),expectedType:expectedType.value||null})).data.data);scanCode.value='';expectedType.value='';message.value=completed.value?'四码绑定完成，聚合码和打印任务已生成':'扫码成功';if(completed.value)await preview()}) }
async function cancel() { if(!task.value||!reason.value.trim()) {message.value='请填写取消原因';failed.value=true;return}try{await showConfirmDialog({title:'取消当前作业',message:'已扫描记录将保留，部件占用会释放。'})}catch(action){if(action!=='cancel')error(action);return}await run(async()=>{await api.post(`/api/production/tasks/${task.value!.id}/cancel`,{reason:reason.value});task.value=undefined;taskId.value='';localStorage.removeItem(storageKey.value);message.value='任务已取消';reason.value=''}) }
async function printBrowser() { await run(async()=>{if(!task.value)return;await api.post(`/api/production/prints/FIRST-${task.value.id}/claim`,{terminal:'browser-manual'});labelFrame.value?.contentWindow?.print();message.value='已打开打印对话框，请核对实物并回报结果'}) }
async function printResult(status:string) { await run(async()=>{await api.post(`/api/production/prints/FIRST-${task.value!.id}/result`,{status,message:status==='SUBMITTED'?'操作员确认已提交浏览器打印':'操作员报告打印失败'});message.value='打印结果已记录'}) }
async function reportIssue(){await run(async()=>{const result=(await api.post('/api/production/issues',{code:issueCode.value,reason:issueReason.value})).data.data;issueId.value=result.id;message.value='异常已登记，相关车辆/部件已隔离';if(imageFile.value)await uploadImage()})}
async function uploadImage(){if(!issueId.value||!imageFile.value)return;const form=new FormData();form.append('file',imageFile.value);await api.post(`/api/production/issues/${issueId.value}/images`,form);message.value='异常和图片均已保存';imageFile.value=undefined}
function pickImage(event:Event){imageFile.value=(event.target as HTMLInputElement).files?.[0]}
onMounted(async()=>{try{skus.value=(await api.get('/api/production/skus')).data.data;skuId.value=options.value[0]?.value;taskId.value=localStorage.getItem(storageKey.value)??'';if(taskId.value)await resume()}catch(e){error(e)}})
</script>

<template>
  <section class="production-station">
    <van-notice-bar text="四码任意顺序扫描，集齐后自动生成聚合码。部件需先登记，新车架请选择车架类型。" wrapable />
    <div v-if="!task || task.status !== 'OPEN'" class="station-card">
      <h3>开始下一辆车</h3>
      <label>生产商品<select v-model="skuId" aria-label="生产商品"><option v-for="item in options" :key="item.value" :value="item.value">{{item.text}}</option></select></label>
      <van-field v-model="batchNo" label="生产批次" placeholder="可选" maxlength="64" />
      <van-button block type="primary" :loading="busy" @click="start">建立生产任务</van-button>
    </div>
    <div class="station-card">
      <van-field v-model="taskId" label="作业任务" placeholder="输入任务号，恢复或交接" />
      <van-button size="small" :disabled="busy || !taskId.trim()" @click="resume">恢复 / 刷新进度</van-button>
    </div>
    <div v-if="task" class="station-card">
      <h3>{{ task.skuCode }} · {{ task.productName }}</h3>
      <p class="task-number">任务：{{ task.id }}</p>
      <van-tag :type="completed ? 'success' : 'primary'">{{ completed ? '组装完成' : task.status === 'OPEN' ? '作业中' : '已取消' }} · {{ task.scans.length }}/4</van-tag>
      <van-cell v-for="(name,type) in labels" :key="type" :title="name" :value="task.scans.find(s=>s.codeType===type)?.code || '待扫描'" />
      <template v-if="task.status === 'OPEN'">
        <label>编码类型<select v-model="expectedType" aria-label="编码类型"><option value="">自动识别</option><option v-for="(name,type) in labels" :key="type" :value="type">{{name}}</option></select></label>
        <van-field ref="scanInput" v-model="scanCode" label="扫描编码" placeholder="扫描任意一种编码后回车" :disabled="busy" autofocus @keyup.enter="scan" />
        <van-button block type="primary" :loading="busy" @click="scan">确认本次扫描</van-button>
        <van-field v-model="reason" label="取消原因" placeholder="更换已扫描编码时，取消后重建" maxlength="500" />
        <van-button size="small" type="danger" plain :disabled="busy" @click="cancel">取消并释放占用</van-button>
      </template>
      <div v-if="completed" class="completed-card">
        <strong>聚合码已生成</strong><p class="task-number">{{ task.qrCode }}</p>
        <p>自动打印终端连接后会领取任务；也可使用下方人工打印。</p>
        <iframe v-if="labelHtml" ref="labelFrame" :srcdoc="labelHtml" title="整车外箱标签" class="label-frame" sandbox="allow-same-origin allow-modals" />
        <van-button type="primary" :disabled="busy" @click="printBrowser">人工打印标签</van-button>
        <van-button size="small" :disabled="busy" @click="printResult('SUBMITTED')">确认已提交打印</van-button>
        <van-button size="small" :disabled="busy" @click="printResult('FAILED')">报告打印失败</van-button>
      </div>
    </div>
    <van-collapse v-model="issuePanel" accordion>
      <van-collapse-item title="登记生产异常 / 上传照片" name="issue">
        <van-field v-model="issueCode" label="异常编码" placeholder="车架或部件SN" maxlength="128" />
        <van-field v-model="issueReason" label="异常原因" type="textarea" maxlength="500" />
        <input type="file" accept="image/jpeg,image/png" @change="pickImage" />
        <van-button block type="danger" plain :loading="busy" @click="reportIssue">登记异常并隔离</van-button>
        <van-button v-if="imageFile" size="small" @click="run(uploadImage)">重试图片上传</van-button>
      </van-collapse-item>
    </van-collapse>
    <van-notice-bar v-if="message" :text="message" wrapable :color="failed ? '#c62828' : '#16794b'" :background="failed ? '#fff0f0' : '#eaf8ef'" />
  </section>
</template>

<style scoped>
select{display:block;width:100%;padding:12px;margin:8px 0;border:1px solid #ccd6e3;border-radius:8px;background:white;font:inherit}label{font-size:14px;color:#46566d}
.production-station{display:grid;gap:14px}.station-card{background:white;padding:14px;border:1px solid #e5eaf0;border-radius:12px}.station-card h3{margin:0 0 10px}.task-number{font-size:12px;overflow-wrap:anywhere;color:#53627a}.station-card .van-button{margin-top:10px}.completed-card{padding-top:14px}.completed-card p{font-size:13px}.label-frame{width:100%;height:440px;border:1px solid #dce3ed;margin:12px 0}.van-collapse input{max-width:100%;margin:12px 0}
</style>
