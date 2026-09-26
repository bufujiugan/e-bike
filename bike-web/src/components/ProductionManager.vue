<script setup lang="ts">
import { computed, ref, reactive, watch, onMounted, onUnmounted } from "vue";
import api from "../api";
import BusinessAttachmentUpload from "./BusinessAttachmentUpload.vue";
import AppIcon from "./AppIcon.vue";
import SkuImportDialog from "./SkuImportDialog.vue";
import "../production.css";
import { ElMessage, ElMessageBox } from "element-plus";
const props = defineProps<{
  username: string;
  canManage: boolean;
  canOperate?: boolean;
  canReportIssue?: boolean;
  canCreateTask?: boolean;
}>();
const emit = defineEmits<{ loaded: [] }>();
const productionTabs = [
  { name: 'tasks', label: '生产任务', icon: 'tasks' },
  { name: 'skus', label: '商品 SKU', icon: 'inventory' },
  { name: 'templates', label: '标签模板', icon: 'tag' },
  { name: 'issues', label: '异常隔离', icon: 'warning' },
  { name: 'quarantine', label: '旧件复检', icon: 'inspect' },
  { name: 'prints', label: '打印队列', icon: 'printer' },
  { name: 'scan-failures', label: '失败扫码', icon: 'trace' },
  { name: 'inventory', label: '生产库存', icon: 'inventory' },
];
const createTaskVisible = ref(false);
const taskDraft = reactive({ skuId: undefined as number | undefined, batchNo: '' });
const pendingTask = ref<{ requestId: string; skuId: number; batchNo: string }>();
function openCreateTask() {
  if (!props.canCreateTask) return;
  if (!pendingTask.value) { taskDraft.skuId = taskSkuId.value; taskDraft.batchNo = ''; }
  createTaskVisible.value = true;
}
async function saveTask() {
  if (!props.canCreateTask || !taskDraft.skuId) { ElMessage.warning('请选择商品规格'); return; }
  await perform(async () => {
    pendingTask.value ??= { requestId: crypto.randomUUID(), skuId: taskDraft.skuId!, batchNo: taskDraft.batchNo.trim() };
    let created;
    try { created = (await api.post('/api/production/tasks', pendingTask.value)).data.data; }
    catch (error) {
      if (api.isAxiosError(error) && error.response && error.response.status < 500) pendingTask.value = undefined;
      throw error;
    }
    pendingTask.value = undefined; createTaskVisible.value = false;
    detail.value = created; ElMessage.success('任务已建立，可在生产工位继续扫码');
    page.value = 1; await load();
  });
}
function queryTasks() { page.value = 1; void perform(load); }
const tab = ref("tasks"),
  loading = ref(false),
  keyword = ref(""),
  page = ref(1),
  total = ref(0);
const tasks = ref<any[]>([]),
  skus = ref<any[]>([]),
  templates = ref<any[]>([]),
  models = ref<any[]>([]),
  issues = ref<any[]>([]),
  prints = ref<any[]>([]);
const skuSearch = ref(''), skuPage = ref(1), skuPageSize = ref(20), skuImportVisible = ref(false);
const filteredSkus = computed(() => {
  const query = skuSearch.value.trim().toLocaleLowerCase();
  return query ? skus.value.filter(row => [row.skuCode, row.modelName, row.productName].some(value => String(value ?? '').toLocaleLowerCase().includes(query))) : skus.value;
});
const pagedSkus = computed(() => filteredSkus.value.slice((skuPage.value - 1) * skuPageSize.value, skuPage.value * skuPageSize.value));
watch([skuSearch, skuPageSize], () => { skuPage.value = 1; });
watch(() => filteredSkus.value.length, count => { skuPage.value = Math.min(skuPage.value, Math.max(1, Math.ceil(count / skuPageSize.value))); });
function refreshSkusAfterImport() { skuPage.value = 1; skuSearch.value = ''; void perform(load); }
const skuVisible = ref(false),
  templateVisible = ref(false),
  editingId = ref<number>(),
  detail = ref<any>(),
  labelHtml = ref(""),
  previewVisible = ref(false);
const previewJob = ref<any>(), labelFrame = ref<HTMLIFrameElement>(), labelReady = ref(false);
const canReportPrint = computed(() => props.canOperate && previewJob.value?.status === 'PROCESSING' && previewJob.value.claimedBy === props.username);
const sku = reactive({
  modelId: 0,
  skuCode: "",
  productName: "",
  specification: "",
  ean: "",
  upc: "",
  templateId: 1,
  enabled: true,
});
const template = reactive({
  id: 1,
  name: "",
  widthMm: 100,
  heightMm: 150,
  layout: "STANDARD",
  heading: "E-BIKE",
});
const issue = reactive({ code: "", reason: "" }),
  lastIssueId = ref(""),
  imageUrl = ref("");
const savedIssueCode = ref(""), issueImagesBusy = ref(false);
const quarantinedParts = ref<any[]>([]);
const scanFailures = ref<any[]>([]);
const inventoryRows = ref<any[]>([]),
  warehouseId = ref<number>(),
  inventorySkuId = ref<number>(),
  warehouses = ref<any[]>([]);
const taskSkuId = ref<number>(), taskStatus = ref(''), taskDates = ref<string[]>([]);
const locationId = ref<number>(), locations = ref<any[]>([]);
async function warehouseChanged() {
  locationId.value=undefined;
  locations.value=warehouseId.value ? (await api.get(`/api/master/warehouses/${warehouseId.value}/locations`)).data.data : [];
  await load();
}
const skuImages = ref<any[]>([]);
const skuImagesBusy = ref(false);
const skuBusy = computed(() => loading.value || skuImagesBusy.value);
const galleryVisible = ref(false), galleryImages = ref<any[]>([]);
async function viewSkuImages(id: number) {
  await perform(async () => {
    galleryImages.value = (await api.get(`/api/production/skus/${id}/images`)).data.data;
    galleryVisible.value = true;
  });
}
async function downloadImage(id: string) {
  await perform(async () => {
    const blob = (await api.get(`/api/production/images/${id}`, {responseType: 'blob'})).data;
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `image-${id}.${blob.type === 'image/png' ? 'png' : blob.type === 'image/webp' ? 'webp' : 'jpg'}`;
    anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  });
}
const states: Record<string, string> = {
  OPEN: "作业中 / 未处理",
  COMPLETED: "已完成",
  CANCELLED: "已取消",
  RESOLVED: "已解除",
  PENDING: "待打印",
  PROCESSING: "已领取",
  SUBMITTED: "已提交打印",
  FAILED: "打印失败",
  UNKNOWN: "结果待核对",
};
let pendingReload = false, pendingWarehouseReload = false, disposed = false;
async function perform(action: () => Promise<void>) {
  if (disposed) return;
  if (loading.value) {
    // Keep the latest read intent; mutations must never be queued or repeated.
    if (action === load || action === warehouseChanged) pendingReload = true;
    if (action === warehouseChanged) pendingWarehouseReload = true;
    return;
  }
  loading.value = true;
  try {
    await action();
  } catch (e) {
    ElMessage.error(
      api.isAxiosError(e)
        ? (e.response?.data?.message ?? "请求失败")
        : String(e),
    );
  } finally {
    loading.value = false;
    if (pendingReload && !disposed) {
      const next = pendingWarehouseReload ? warehouseChanged : load;
      pendingReload = false; pendingWarehouseReload = false;
      await perform(next);
    }
  }
}
async function load() {
  await loadData();
  emit('loaded');
}
async function loadData() {
  if (tab.value === "inventory") {
    inventoryRows.value = (
      await api.get("/api/production/inventory", {
        params: { skuId: inventorySkuId.value, warehouseId: warehouseId.value, locationId: locationId.value },
      })
    ).data.data;
    warehouses.value = (await api.get("/api/master/warehouses")).data.data;
    return;
  }
  if (tab.value === "quarantine") {
    const result = (
      await api.get("/api/production/quarantined-parts", {params: {keyword: keyword.value || undefined, page: page.value}})
    ).data.data;
    quarantinedParts.value = result.records;
    total.value = result.total;
    return;
  }
  const path =
    tab.value === "tasks"
      ? "tasks"
      : tab.value === "issues"
        ? "issues"
        : tab.value === "prints"
          ? "prints"
          : tab.value === "scan-failures" ? "scan-failures" : null;
  if (path) {
    const result = (
      await api.get(`/api/production/${path}`, {
        params: { keyword: keyword.value || undefined, page: page.value, ...(path==='tasks' ? {skuId:taskSkuId.value,status:taskStatus.value||undefined,fromDate:taskDates.value?.[0],toDate:taskDates.value?.[1]} : {}) },
      })
    ).data.data;
    total.value = result.total;
    if (path === "tasks") tasks.value = result.records;
    if (path === "issues") issues.value = result.records;
    if (path === "prints") prints.value = result.records;
    if (path === "scan-failures") scanFailures.value = result.records;
  } else {
    skus.value = (await api.get("/api/production/skus")).data.data;
    templates.value = (await api.get("/api/production/templates")).data.data;
  }
}
async function changeTab() {
  page.value = 1;
  keyword.value = "";
  await perform(load);
}
function editSku(row?: any) {
  editingId.value = row?.id;
  skuImages.value = [];
  Object.assign(
    sku,
    row
      ? { ...row, enabled: !!row.enabled }
      : {
          modelId: models.value[0]?.id ?? 0,
          skuCode: "",
          productName: "",
          specification: "",
          ean: "",
          upc: "",
          templateId: 1,
          enabled: true,
        },
  );
  skuVisible.value = true;
  if (row)
    void perform(async () => {
      skuImages.value = (
        await api.get(`/api/production/skus/${row.id}/images`)
      ).data.data;
    });
}
function skuImageSaved(row: any) { if (!skuImages.value.some(item => item.id === row.id)) skuImages.value.push(row) }
async function saveSku() {
  if (skuImagesBusy.value) return;
  await perform(async () => {
    await api[editingId.value ? "put" : "post"](
      `/api/production/skus${editingId.value ? "/" + editingId.value : ""}`,
      sku,
    );
    skuVisible.value = false;
    await load();
    ElMessage.success("SKU已保存");
  });
}
function editTemplate(row: any) {
  Object.assign(template, row);
  templateVisible.value = true;
}
async function saveTemplate() {
  await perform(async () => {
    await api.put(`/api/production/templates/${template.id}`, template);
    templateVisible.value = false;
    await load();
    ElMessage.success("模板新版本已发布，旧标签保持原版");
  });
}
async function viewTask(id: string) {
  await perform(async () => {
    detail.value = (await api.get(`/api/production/tasks/${id}`)).data.data;
  });
}
async function preview(id: string) {
  await perform(async () => {
    labelReady.value = false;
    previewJob.value = (await api.get(`/api/production/prints/${id}`)).data.data;
    labelHtml.value = (
      await api.get(`/api/production/prints/${id}/label`, {
        responseType: "text",
      })
    ).data;
    previewVisible.value = true;
  });
}
async function printManual() {
  await perform(async () => {
    if (!previewJob.value || !labelReady.value || !labelFrame.value?.contentWindow) throw new Error('标签尚未加载完成');
    previewJob.value = (await api.post(`/api/production/prints/${previewJob.value.id}/claim`, {terminal:'web-manual'})).data.data;
    labelFrame.value.contentWindow.print();
    ElMessage.info('请核对打印结果，再回报所选任务');
    await load();
  });
}
async function reportManual(status: string) {
  await perform(async () => {
    if (!canReportPrint.value) throw new Error('只能回报当前账号领取的任务');
    await api.post(`/api/production/prints/${previewJob.value.id}/result`, {status,message:status==='SUBMITTED'?'操作员确认已提交浏览器打印':status==='FAILED'?'操作员确认打印失败':'实物结果待核对'});
    previewJob.value = (await api.get(`/api/production/prints/${previewJob.value.id}`)).data.data;
    await load();
    ElMessage.success('打印结果已记录');
  });
}
async function resolve(row: any) {
  try {
    const r = await ElMessageBox.prompt(
      "请填写检查结果和解除原因",
      "解除隔离",
      { inputValidator: (v) => !!v?.trim() || "原因不能为空" },
    );
    await perform(async () => {
      await api.post(`/api/production/issues/${row.id}/resolve`, {
        reason: r.value,
      });
      await load();
    });
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error("操作未完成");
  }
}
async function reprint(row: any) {
  try {
    const r = await ElMessageBox.prompt(
      "确认已核对实物，再填写补打原因",
      "新建补打任务",
      { inputValidator: (v) => !!v?.trim() || "原因不能为空" },
    );
    await perform(async () => {
      await api.post(`/api/production/prints/${row.id}/reprint`, {
        reason: r.value,
      });
      await load();
    });
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error("操作未完成");
  }
}
async function createIssue() {
  if (issueImagesBusy.value) return;
  await perform(async () => {
    const code = issue.code;
    lastIssueId.value = (
      await api.post("/api/production/issues", issue)
    ).data.data.id;
    savedIssueCode.value = code;
    issue.code = "";
    issue.reason = "";
    await load();
    ElMessage.success("异常已登记并隔离");
  });
}
let imageRequestVersion = 0;
function closeImage() {
  imageRequestVersion++;
  if (imageUrl.value) URL.revokeObjectURL(imageUrl.value);
  imageUrl.value = '';
}
async function showImage(id: string) {
  await perform(async () => {
    const requestVersion = ++imageRequestVersion;
    let response;
    try {
      response = await api.get(`/api/production/images/${id}`, { responseType: "blob" });
    } catch (error) {
      if (requestVersion === imageRequestVersion) throw error;
      return;
    }
    if (requestVersion !== imageRequestVersion) return;
    if (imageUrl.value) URL.revokeObjectURL(imageUrl.value);
    imageUrl.value = URL.createObjectURL(response.data);
  });
}
async function releasePart(row: any) {
  try {
    const r = await ElMessageBox.prompt(
      "仅复检合格后放行，请填写复检结果",
      "旧件复检放行",
      { inputValidator: (v) => !!v?.trim() || "结果不能为空" },
    );
    await perform(async () => {
      await api.post(
        `/api/production/quarantined-parts/${encodeURIComponent(row.code)}/release`,
        { reason: r.value },
      );
      await load();
    });
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error("操作未完成");
  }
}
onUnmounted(() => { disposed = true; pendingReload = false; pendingWarehouseReload = false; closeImage(); });
onMounted(() =>
  perform(async () => {
    skus.value = (await api.get("/api/production/skus")).data.data;
    templates.value = (await api.get("/api/production/templates")).data.data;
    // 车型清单仅用于有生产管理权限的岗位编辑 SKU。
    if (props.canManage) models.value = (await api.get("/api/master/models")).data.data;
    await load();
  }),
);
</script>

<template>
  <section class="production-manager" v-loading="loading">
    <div class="production-hero-card">
      <header class="production-hero">
        <span class="production-heading-icon"><AppIcon name="production" /></span>
        <div class="production-hero-copy"><h2>生产中心</h2><p>四码聚合：作业交接 · 标签打印 · 质量隔离</p></div>
        <div class="production-hero-visual" aria-hidden="true">
          <span class="manufacturing-caption">SMART MANUFACTURING<br />FOR E-BIKE</span>
          <div class="production-bike-scene"></div>
          <div class="production-capabilities"><span><AppIcon name="chart" />高效生产</span><span><AppIcon name="roles" />品质可追溯</span><span><AppIcon name="inventory" />库存实时化</span></div>
        </div>
      </header>
      <el-tabs v-model="tab" class="production-tabs" @tab-change="changeTab">
        <el-tab-pane v-for="item in productionTabs" :key="item.name" :name="item.name" :label="item.label"><template #label><span class="production-tab-label"><AppIcon :name="item.icon" />{{ item.label }}</span></template></el-tab-pane>
      </el-tabs>
    </div>
    <div class="production-data-card">
    <div class="toolbar production-toolbar" :class="{ 'sku-toolbar': tab !== 'tasks' }">
      <template v-if="tab === 'inventory'"><el-select
          v-model="inventorySkuId"
          clearable
          placeholder="所有商品规格（SKU）"
          @change="perform(load)"
          ><el-option
            v-for="s in skus"
            :key="s.id"
            :value="s.id"
            :label="s.skuCode" /></el-select
        ><el-select
          v-model="warehouseId"
          clearable
          placeholder="所有仓库"
          @change="perform(warehouseChanged)"
          ><el-option
            v-for="w in warehouses"
            :key="w.id"
            :value="w.id"
            :label="w.name"
        /></el-select>
        <el-select v-model="locationId" clearable placeholder="所有库位" :disabled="!warehouseId" @change="perform(load)"><el-option v-for="location in locations" :key="location.id" :label="location.name" :value="location.id" /></el-select>
      </template>
      <template v-if="tab === 'tasks'">
        <el-select v-model="taskSkuId" class="task-sku-filter" clearable aria-label="商品规格筛选" placeholder="所有商品规格（SKU）" @change="queryTasks"><template #prefix><AppIcon name="inventory" /></template><el-option v-for="s in skus" :key="s.id" :value="s.id" :label="s.skuCode" /></el-select>
        <el-select v-model="taskStatus" class="task-status-filter" clearable aria-label="任务状态筛选" placeholder="所有状态" @change="queryTasks"><template #prefix><AppIcon name="layers" /></template><el-option label="作业中" value="OPEN"/><el-option label="已完成" value="COMPLETED"/><el-option label="已取消" value="CANCELLED"/></el-select>
        <el-button v-if="canCreateTask" type="primary" class="create-task-button" @click="openCreateTask"><AppIcon name="plus" />创建任务</el-button>
      </template>
      <el-input v-if="['tasks', 'issues', 'quarantine', 'scan-failures'].includes(tab)" v-model="keyword" class="production-search" clearable aria-label="搜索任务、编码或批次" placeholder="搜索任务、编码或批次" @keyup.enter="queryTasks" @clear="queryTasks"><template #prefix><AppIcon name="search" /></template></el-input>
      <el-input v-if="tab === 'skus'" v-model="skuSearch" class="sku-search" clearable aria-label="搜索 SKU、车型或商品名称" placeholder="搜索 SKU、车型或商品名称"><template #prefix><AppIcon name="search" /></template></el-input>
      <el-button class="production-refresh" @click="queryTasks"><AppIcon name="refresh" />刷新</el-button>
      <el-button v-if="tab === 'skus' && canManage" class="sku-import-button" @click="skuImportVisible = true"><AppIcon name="download" />导入 SKU</el-button>
      <el-button v-if="tab === 'skus' && canManage" type="primary" @click="editSku()"><AppIcon name="plus" />新增 SKU</el-button>
    </div>

    <template v-if="tab === 'inventory'">
      <el-table :data="inventoryRows"
        ><el-table-column prop="skuCode" label="SKU" /><el-table-column
          prop="productName"
          label="商品" /><el-table-column
          prop="warehouseName"
          label="仓库" /><el-table-column
          prop="locationName"
          label="库位" /><el-table-column
          prop="physicalQty"
          label="实物数量" /><el-table-column
          prop="quarantinedQty"
          label="其中隔离"
      /></el-table>
      <p>
        包含全部已入库车辆，旧车标记“历史未关联”；隔离车辆不能流转。车型锁定数量请在库存管理查看。
      </p></template
    >
    <el-table v-if="tab === 'quarantine'" :data="quarantinedParts"
      ><el-table-column prop="code" label="隔离旧件编码" /><el-table-column
        prop="codeType"
        label="类型"
      /><el-table-column prop="qcStatus" label="质检状态" /><el-table-column
        v-if="canManage"
        label="操作"
        ><template #default="s"
          ><el-button link type="primary" @click="releasePart(s.row)"
            >复检合格放行</el-button
          ></template
        ></el-table-column
      ></el-table
    >
    <div v-if="tab === 'tasks'" class="production-date-filter"><span>创建日期</span><el-date-picker v-model="taskDates" aria-label="创建日期范围" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" @change="queryTasks" /><el-button link @click="taskSkuId = undefined; taskStatus = ''; taskDates = []; keyword = ''; queryTasks()">重置筛选</el-button></div>
    <el-table v-if="tab === 'scan-failures'" :data="scanFailures">
      <el-table-column prop="createdAt" label="时间" min-width="170" />
      <el-table-column prop="taskId" label="任务" min-width="210" />
      <el-table-column prop="code" label="输入编码" min-width="160" />
      <el-table-column prop="codeType" label="类型" />
      <el-table-column prop="operatorName" label="操作人" />
      <el-table-column prop="errorCode" label="失败代码" min-width="170" />
      <el-table-column prop="reason" label="原因" min-width="220" />
    </el-table>
    <template v-if="tab === 'tasks'">
      <p class="task-list-hint"><AppIcon name="info" />已完成 → 作业中 → 已取消，同状态按修改时间从新到旧排列。聚合码在完成组装后生成。</p>
      <el-table :data="tasks" class="production-task-table" scrollbar-always-on stripe max-height="max(260px, calc(100vh - 620px))" row-key="id" empty-text="暂无生产任务">
        <el-table-column prop="id" label="任务号" min-width="80" show-overflow-tooltip />
        <el-table-column prop="skuCode" label="商品规格（SKU）" min-width="100" show-overflow-tooltip>
          <template #header><el-tooltip content="SKU是商品规格编号，用来区分同一车型的颜色、电池容量等配置。"><span>商品规格（SKU）</span></el-tooltip></template>
        </el-table-column>
        <el-table-column prop="batchNo" label="批次" min-width="70" show-overflow-tooltip />
        <el-table-column v-for="code in [{key:'qrCode',label:'聚合码'},{key:'frameSn',label:'车架码'},{key:'motorSn',label:'电机码'},{key:'batterySn',label:'电池码'},{key:'displaySn',label:'仪表码'}]"
          :key="code.key" :prop="code.key" :label="code.label" :min-width="code.key === 'qrCode' ? 105 : 85" show-overflow-tooltip>
          <template #default="{ row }">{{ row[code.key] || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="88"><template #default="{ row }"><el-tag :type="row.status === 'COMPLETED' ? 'success' : row.status === 'OPEN' ? 'warning' : 'info'" size="small" class="task-status-badge"><i></i>{{ states[row.status] }}</el-tag></template></el-table-column>
        <el-table-column label="修改时间 ↓" width="145"><template #default="{ row }">{{ row.updatedAt?.replace('T', ' ').slice(0, 19) || '—' }}</template></el-table-column>
        <el-table-column prop="createdBy" label="创建人" width="70" show-overflow-tooltip />
        <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="viewTask(row.id)">查看进度</el-button></template></el-table-column>
      </el-table>
    </template>
    <template v-if="tab === 'skus'">
      <el-table :data="pagedSkus" class="production-sku-table" scrollbar-always-on row-key="id" max-height="max(240px, calc(100vh - 540px))" :empty-text="skuSearch ? '没有匹配的商品 SKU' : '暂无商品 SKU'">
        <el-table-column prop="skuCode" label="SKU" min-width="160" show-overflow-tooltip />
        <el-table-column prop="modelName" label="车型" min-width="150" show-overflow-tooltip />
        <el-table-column prop="productName" label="商品名称" min-width="170" show-overflow-tooltip />
        <el-table-column prop="specification" label="规格" min-width="100" show-overflow-tooltip />
        <el-table-column prop="ean" label="EAN" min-width="100" show-overflow-tooltip />
        <el-table-column prop="upc" label="UPC" min-width="100" show-overflow-tooltip />
        <el-table-column label="状态" width="80"><template #default="{ row }"><span class="sku-status" :class="{ disabled: !row.enabled }">{{ row.enabled ? '启用' : '停用' }}</span></template></el-table-column>
        <el-table-column label="操作" width="160" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="viewSkuImages(row.id)">商品图片</el-button><el-button v-if="canManage" link type="primary" @click="editSku(row)">编辑</el-button></template></el-table-column>
      </el-table>
      <footer class="production-pagination sku-pagination"><span>共 <strong>{{ filteredSkus.length }}</strong> 条记录</span><el-pagination v-model:current-page="skuPage" v-model:page-size="skuPageSize" :page-sizes="[20, 50, 100]" :total="filteredSkus.length" background layout="sizes,prev,pager,next,jumper" /></footer>
    </template>
    <el-table v-if="tab === 'templates'" :data="templates"
      ><el-table-column prop="name" label="标签模板" /><el-table-column
        prop="widthMm"
        label="宽 mm"
      /><el-table-column prop="heightMm" label="高 mm" /><el-table-column
        prop="heading"
        label="标题"
      /><el-table-column prop="version" label="版本" /><el-table-column
        v-if="canManage"
        label="操作"
        ><template #default="s"
          ><el-button link type="primary" @click="editTemplate(s.row)"
            >配置版式</el-button
          ></template
        ></el-table-column
      ></el-table
    >
    <template v-if="tab === 'issues'">
      <el-form v-if="canReportIssue" inline
        ><el-form-item label="编码"
          ><el-input v-model="issue.code" maxlength="200" placeholder="整车聚合码或部件码" /></el-form-item
        ><el-form-item label="异常原因"
          ><el-input v-model="issue.reason" maxlength="500" /></el-form-item
        ><el-button type="danger" :disabled="issueImagesBusy" @click="createIssue">登记并隔离</el-button></el-form>
      <p v-if="canReportIssue && lastIssueId">照片归属：{{ savedIssueCode }} · 异常 {{ lastIssueId }}</p>
      <BusinessAttachmentUpload v-if="canReportIssue && lastIssueId" :key="lastIssueId" :endpoint="`/api/production/issues/${lastIssueId}/images`" images-only @saved="perform(load)" @busy="issueImagesBusy = $event" />
      <el-table :data="issues"
        ><el-table-column label="聚合码 / 部件码" min-width="200" show-overflow-tooltip><template #default="{ row }">{{ row.vehicleQrCode || row.code }}</template></el-table-column><el-table-column
          prop="reason"
          label="异常原因"
        /><el-table-column label="状态"
          ><template #default="s">{{
            states[s.row.status]
          }}</template></el-table-column
        ><el-table-column prop="createdBy" label="登记人" /><el-table-column
          prop="resolution"
          label="解除说明"
        /><el-table-column label="操作"
          ><template #default="s"
            ><el-button
              v-for="a in s.row.attachments"
              :key="a.id"
              link
              @click="showImage(a.id)"
              >查看图片</el-button
            ><el-button
              v-if="canManage && s.row.status === 'OPEN'"
              link
              type="primary"
              @click="resolve(s.row)"
              >解除隔离</el-button
            ></template
          ></el-table-column
        ></el-table
      >
    </template>
    <template v-if="tab === 'prints'"
      ><el-alert
        title="自动打印终端领取待打印任务。已提交表示送入打印队列；超时未回报需核对实物后补打。"
        type="info"
        :closable="false"
      /><el-table :data="prints"
        ><el-table-column
          prop="id"
          label="任务号"
          min-width="280"
        /><el-table-column label="状态"
          ><template #default="s">{{
            states[s.row.status]
          }}</template></el-table-column
        ><el-table-column prop="reason" label="打印原因" /><el-table-column
          prop="terminal"
          label="打印终端"
        /><el-table-column prop="message" label="结果" /><el-table-column
          label="操作"
          ><template #default="s"
            ><el-button link @click="preview(s.row.id)">预览</el-button
            ><el-button
              v-if="
                canOperate && !['PENDING', 'PROCESSING'].includes(s.row.status)
              "
              link
              type="primary"
              @click="reprint(s.row)"
              >补打</el-button
            ></template
          ></el-table-column
        ></el-table
      ></template
    >
    <footer v-if="['tasks', 'issues', 'prints', 'quarantine', 'scan-failures'].includes(tab)" class="production-pagination">
      <span>共 <strong>{{ total }}</strong> 条记录</span>
      <div><span class="production-page-size">50 条 / 页</span><el-pagination v-model:current-page="page" :page-size="50" :total="total" background layout="prev,pager,next,jumper" @current-change="perform(load)" /></div>
    </footer>
    </div>
    <SkuImportDialog v-if="canManage" v-model="skuImportVisible" @imported="refreshSkusAfterImport" />
    <el-dialog v-model="createTaskVisible" title="创建生产任务" width="520px" :close-on-click-modal="false" :close-on-press-escape="!loading" :show-close="!loading">
      <p class="production-dialog-help">选择商品规格并填写生产批次，创建后在生产工位扫描四码完成组装。</p>
      <el-alert v-if="pendingTask" title="上次请求未确认，请重试原任务，系统会防止重复创建。" type="info" :closable="false" />
      <el-form label-position="top" @submit.prevent="saveTask">
        <el-form-item label="商品规格（SKU）" required><el-select v-model="taskDraft.skuId" :disabled="loading || !!pendingTask" filterable placeholder="请选择商品规格"><el-option v-for="s in skus.filter(item => item.enabled)" :key="s.id" :value="s.id" :label="s.skuCode + ' · ' + s.productName" /></el-select></el-form-item>
        <el-form-item label="生产批次"><el-input v-model="taskDraft.batchNo" :disabled="loading || !!pendingTask" maxlength="64" placeholder="选填，用于后续筛选和追溯" /></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="loading" @click="createTaskVisible = false">取消</el-button><el-button type="primary" :loading="loading" @click="saveTask">{{ pendingTask ? '重试创建' : '创建任务' }}</el-button></template>
    </el-dialog>
    <el-dialog v-model="galleryVisible" title="商品图片" width="550px">
      <el-empty v-if="!galleryImages.length" description="暂无商品图片" />
      <div v-for="(a, index) in galleryImages" :key="a.id" class="toolbar">
        <span>图片 {{ index + 1 }}</span><el-button @click="showImage(a.id)">查看</el-button><el-button @click="downloadImage(a.id)">下载</el-button>
      </div>
    </el-dialog>
    <el-dialog v-model="skuVisible" :title="editingId ? '编辑商品 SKU' : '新增商品 SKU'" width="760px" class="sku-editor-dialog" :close-on-click-modal="false" :close-on-press-escape="!skuBusy" :show-close="!skuBusy">
      <p class="sku-editor-intro">SKU 是商品规格编号，用于区分同一车型的颜色、电池容量等配置。</p>
      <el-form class="sku-editor-form" label-position="top" :disabled="loading">
        <el-form-item label="车型" required>
          <el-select v-model="sku.modelId" :disabled="!!editingId" filterable placeholder="请选择车型">
            <el-option v-for="m in models" :key="m.id" :label="m.name" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="SKU 编码" required>
          <el-input v-model="sku.skuCode" :disabled="!!editingId" maxlength="64" placeholder="填写唯一的商品规格编号" />
        </el-form-item>
        <el-form-item label="商品名称" required><el-input v-model="sku.productName" maxlength="100" /></el-form-item>
        <el-form-item label="规格"><el-input v-model="sku.specification" maxlength="100" placeholder="如颜色、电池容量等" /></el-form-item>
        <el-form-item label="EAN"><el-input v-model="sku.ean" maxlength="32" /></el-form-item>
        <el-form-item label="UPC"><el-input v-model="sku.upc" maxlength="32" /></el-form-item>
        <el-form-item label="标签模板" required>
          <el-select v-model="sku.templateId"><el-option v-for="t in templates" :key="t.id" :label="t.name" :value="t.id" /></el-select>
        </el-form-item>
        <el-form-item label="状态"><el-switch v-model="sku.enabled" aria-label="启用商品 SKU" /><span class="sku-enabled-label">{{ sku.enabled ? '启用' : '停用' }}</span></el-form-item>
      </el-form>
      <section class="sku-editor-images" aria-label="商品图片管理">
        <h3>商品图片</h3>
        <template v-if="editingId">
          <div v-if="skuImages.length" class="sku-image-links">
            <el-button v-for="(a, index) in skuImages" :key="a.id" :disabled="skuBusy" @click="showImage(a.id)">查看图片 {{ index + 1 }}</el-button>
          </div>
          <p v-else class="sku-editor-intro">暂无商品图片，可在下方选择并上传。</p>
          <BusinessAttachmentUpload v-if="skuVisible" :key="editingId" :endpoint="`/api/production/skus/${editingId}/images`" images-only :disabled="loading" @saved="skuImageSaved" @busy="skuImagesBusy = $event" />
        </template>
        <p v-else class="sku-editor-intro">保存商品后可上传产品图片。</p>
      </section>
      <template #footer>
        <el-button :disabled="skuBusy" @click="skuVisible = false">取消</el-button>
        <el-button type="primary" :loading="loading" :disabled="skuImagesBusy" @click="saveSku">保存</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="templateVisible" title="标签版式" width="500px"
      ><el-form label-width="100px"
        ><el-form-item label="模板名称"
          ><el-input v-model="template.name" maxlength="100" /></el-form-item
        ><el-form-item label="标签标题"
          ><el-input v-model="template.heading" maxlength="100" /></el-form-item
        ><el-form-item label="宽度 mm"
          ><el-input-number
            v-model="template.widthMm"
            :min="60"
            :max="210" /></el-form-item
        ><el-form-item label="高度 mm"
          ><el-input-number
            v-model="template.heightMm"
            :min="60"
            :max="297" /></el-form-item
        ><el-form-item label="版式"
          ><el-select v-model="template.layout"
            ><el-option label="标准" value="STANDARD" /><el-option
              label="紧凑"
              value="COMPACT" /></el-select></el-form-item></el-form
      ><template #footer
        ><el-button type="primary" :loading="loading" @click="saveTemplate"
          >发布新版本</el-button
        ></template
      ></el-dialog
    >
    <el-dialog
      :model-value="!!detail"
      title="作业详情与交接"
      @close="detail = undefined"
      ><template v-if="detail"
        ><p>任务号：{{ detail.id }}</p>
        <p>
          {{ detail.skuCode }} · {{ detail.productName }} ·
          {{ states[detail.status] }}
        </p>
        <el-table :data="detail.scans"
          ><el-table-column prop="codeType" label="类型" /><el-table-column
            prop="code"
            label="编码" /><el-table-column prop="operatorName" label="扫码人"
        /></el-table>
        <p v-if="detail.qrCode">聚合码：{{ detail.qrCode }}</p></template
      ></el-dialog
    >
    <el-dialog v-model="previewVisible" title="外箱标签预览"
      ><p v-if="previewJob">{{ states[previewJob.status] }} · {{ previewJob.message || previewJob.reason }}</p><iframe
        ref="labelFrame"
        @load="labelReady = true"
        :srcdoc="labelHtml"
        title="标签"
        sandbox="allow-same-origin allow-modals"
        style="width: 100%; height: 620px; border: 0"
    /><template #footer>
      <el-button v-if="canOperate && previewJob?.status === 'PENDING'" :disabled="loading || !labelReady" type="primary" @click="printManual">人工打印此任务</el-button>
      <el-button v-if="canReportPrint" :disabled="loading" @click="reportManual('SUBMITTED')">确认已提交打印</el-button>
      <el-button v-if="canReportPrint" :disabled="loading" @click="reportManual('FAILED')">确认失败</el-button>
      <el-button v-if="canReportPrint" :disabled="loading" @click="reportManual('UNKNOWN')">结果待核对</el-button>
    </template></el-dialog>
    <el-dialog
      :model-value="!!imageUrl"
      title="图片预览"
      @close="closeImage"
      ><img :src="imageUrl" alt="业务图片" style="max-width: 100%"
    /></el-dialog>
  </section>
</template>
<style scoped>
.toolbar {
  display: flex;
  gap: 10px;
  margin: 12px 0;
}
.toolbar .el-input {
  max-width: 340px;
}
.el-pagination {
  margin-top: 20px;
}
.el-form {
  margin-top: 12px;
}
a {
  color: #1677c8;
}
</style>
