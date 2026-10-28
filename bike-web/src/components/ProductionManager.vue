<script setup lang="ts">
import { createUuid } from '../utils/uuid'
import { computed, ref, reactive, watch, onMounted, onUnmounted } from "vue";
import api from "../api";
import BusinessAttachmentUpload from "./BusinessAttachmentUpload.vue";
import AppIcon from "./AppIcon.vue";
import SkuImportDialog from "./SkuImportDialog.vue";
import "../production.css";
import ProductionOverview from './business/ProductionOverview.vue';
import ProductionOrderWorkbench from './ProductionOrderWorkbench.vue';
import SectionHeader from './common/SectionHeader.vue';
import AlertCard from './common/AlertCard.vue';
import DataTable from './table/DataTable.vue';
import Pagination from './table/Pagination.vue';
import StatusTag from './business/StatusTag.vue';
import TraceDetails, { type TraceData } from './TraceDetails.vue';
import { hasPermission, type PermissionContext } from '../permissions';
import { ElMessage, ElMessageBox } from "element-plus";
const props = defineProps<{
  view: string;
  username: string;
  canManage: boolean;
  canOperate?: boolean;
  canReportIssue?: boolean;
  canCreateTask?: boolean;
  context?: PermissionContext | null;
  initialSkuId?: number;
  initialKeyword?: string;
}>();
const emit = defineEmits<{ loaded: []; navigate: [key: string]; order: [orderNo: string]; continue: [stage: string, sku: { id: number; skuCode: string; modelId: number }] }>();
const vehicleJourney = ref<any>(), vehicleTrace = ref<TraceData>(), vehiclePrints = ref<any[]>([]), journeyBusy = ref(false), journeyError = ref('');
let journeyVersion = 0;
function continueWithSku(stage: string, row: any) {
  detail.value = undefined;
  const selected = skus.value.find(sku => sku.id === Number(row.skuId ?? row.id));
  emit('continue', stage, { id: Number(row.skuId ?? row.id), skuCode: row.skuCode, modelId: Number(row.modelId ?? selected?.modelId) });
}
async function openVehicleJourney(row: any) {
  const version = ++journeyVersion;
  vehicleJourney.value = row; vehicleTrace.value = undefined; vehiclePrints.value = []; journeyBusy.value = true; journeyError.value = '';
  const requests = [api.get(`/api/production/tasks/${encodeURIComponent(row.id)}/prints`)];
  if (row.qrCode && hasPermission(props.context, 'TRACE_READ')) requests.push(api.get(`/api/trace/qr/${encodeURIComponent(row.qrCode)}`));
  const results = await Promise.allSettled(requests);
  if (version !== journeyVersion) return;
  results.forEach((result, index) => {
    if (result.status === 'fulfilled') {
      if (index === 0) vehiclePrints.value = result.value.data.data;
      else vehicleTrace.value = result.value.data.data;
    } else journeyError.value += (api.isAxiosError(result.reason) ? result.reason.response?.data?.message ?? '关联记录加载失败' : '关联记录加载失败') + '；';
  });
  journeyBusy.value = false;
}
const productionTabs = [
  { name: 'tasks', label: '生产工单', icon: 'tasks' },
  { name: 'skus', label: '商品 SKU', icon: 'inventory' },
  { name: 'templates', label: '标签模板', icon: 'tag' },
  { name: 'issues', label: '异常隔离', icon: 'warning' },
  { name: 'quarantine', label: '旧件复检', icon: 'inspect' },
  { name: 'prints', label: '打印队列', icon: 'printer' },
  { name: 'scan-failures', label: '失败扫码', icon: 'trace' },
  { name: 'inventory', label: '生产库存', icon: 'inventory' },
];
const productionViews = new Set(['tasks', 'skus', 'templates', 'issues', 'quarantine', 'prints', 'scan-failures', 'inventory']);
const isOverview = props.view === 'overview';
const moduleTableMaxHeight = 'max(240px, calc(100dvh - 480px))';
const formatDateTime = (value?: string | null) => value ? value.replace('T', ' ').slice(0, 19) : '—';
const createTaskVisible = ref(false);
const printRefreshKey = ref(0);
async function prepareWorkOrderSku() {
  await perform(async () => {
    models.value = (await api.get('/api/master/models')).data.data;
    templates.value = (await api.get('/api/production/templates')).data.data;
    editSku();
    if (templates.value.length) sku.templateId = templates.value[0].id;
  });
}
function refreshWorkOrderList() { void perform(load); }
function scrollToTaskList() {
  emit('navigate', 'production-tasks');
}
function showIssueRecords() {
  emit('navigate', 'production-issues');
}
const readError = ref('');
const taskDraft = reactive({ skuId: undefined as number | undefined, batchNo: '', plannedQuantity: 100 });
const pendingTask = ref<{ requestId: string; skuId: number; batchNo: string; plannedQuantity: number }>();
function openCreateTask() {
  if (!props.canManage) return;
  if (!pendingTask.value) { taskDraft.skuId = taskSkuId.value; taskDraft.batchNo = ''; taskDraft.plannedQuantity = 100; }
  createTaskVisible.value = true;
}
async function saveTask() {
  if (!props.canManage || !taskDraft.skuId) { ElMessage.warning('请选择商品规格'); return; }
  if (!taskDraft.batchNo.trim()) { ElMessage.warning('请填写生产批次'); return; }
  if (!Number.isInteger(taskDraft.plannedQuantity) || taskDraft.plannedQuantity < 1) { ElMessage.warning('计划数量必须大于0'); return; }
  await perform(async () => {
    pendingTask.value ??= { requestId: createUuid(), skuId: taskDraft.skuId!, batchNo: taskDraft.batchNo.trim(), plannedQuantity: taskDraft.plannedQuantity };
    let created;
    try { created = (await api.post('/api/production/work-orders', pendingTask.value)).data.data; }
    catch (error) {
      if (api.isAxiosError(error) && error.response && error.response.status < 500) pendingTask.value = undefined;
      throw error;
    }
    pendingTask.value = undefined; createTaskVisible.value = false;
    detail.value = created; detailTaskPage.value = 1; ElMessage.success('生产单已创建，可在当前页面开始扫码');
    page.value = 1; await load();
  });
}
function queryTasks() { page.value = 1; void perform(load); }
const tab = ref(isOverview ? 'tasks' : productionViews.has(props.view) ? props.view : 'tasks'),
  loading = ref(false),
  keyword = ref(props.initialKeyword ?? ""),
  page = ref(1),
  total = ref(0);
const tasks = ref<any[]>([]),
  skus = ref<any[]>([]),
  templates = ref<any[]>([]),
  models = ref<any[]>([]),
  issues = ref<any[]>([]),
  prints = ref<any[]>([]);
const skuSearch = ref(props.initialKeyword ?? ''), skuPage = ref(1), skuPageSize = ref(20), skuImportVisible = ref(false);
const filteredSkus = computed(() => {
  const query = skuSearch.value.trim().toLocaleLowerCase();
  return query ? skus.value.filter(row => [row.skuCode, row.modelCode, row.modelName, row.productName, row.category].some(value => String(value ?? '').toLocaleLowerCase().includes(query))) : skus.value;
});
const pagedSkus = computed(() => filteredSkus.value.slice((skuPage.value - 1) * skuPageSize.value, skuPage.value * skuPageSize.value));
watch([skuSearch, skuPageSize], () => { skuPage.value = 1; });
watch(() => filteredSkus.value.length, count => { skuPage.value = Math.min(skuPage.value, Math.max(1, Math.ceil(count / skuPageSize.value))); });
function refreshSkusAfterImport() { skuPage.value = 1; skuSearch.value = ''; void perform(load); }
const skuVisible = ref(false),
  templateVisible = ref(false),
  editingId = ref<number>(),
  detail = ref<any>(),
  detailTaskPage = ref(1),
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
  category: "",
  batteryDescription: "",
  packageQuantity: undefined as number | undefined,
  cartonSizeCm: "",
  grossWeightKg: undefined as number | undefined,
  netWeightKg: undefined as number | undefined,
});
type SkuNameDraft = {
  id?: number;
  clientKey: string;
  name: string;
  ean: string;
  sn: string;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
  eanBarcodeDataUrl?: string;
  barcodeLoading?: boolean;
};
const skuNamesVisible = ref(false), skuNamesBusy = ref(false), selectedSku = ref<any>();
const skuNames = ref<SkuNameDraft[]>([]);
function emptySkuName(): SkuNameDraft {
  return { clientKey: createUuid(), name: "", ean: "", sn: "", enabled: true };
}
function addSkuName() { skuNames.value.push(emptySkuName()); }
function removeUnsavedSkuName(row: SkuNameDraft) {
  if (!row.id) skuNames.value = skuNames.value.filter(item => item.clientKey !== row.clientKey);
}
async function loadSkuNames() {
  if (!selectedSku.value) return;
  const rows = (await api.get(`/api/production/skus/${selectedSku.value.id}/names`)).data.data;
  skuNames.value = rows.map((row: any) => ({ ...row, clientKey: `name-${row.id}`, enabled: !!row.enabled, ean: row.ean ?? "", sn: row.sn ?? "" }));
}
async function openSkuNames(row: any) {
  selectedSku.value = row;
  skuNamesVisible.value = true;
  skuNamesBusy.value = true;
  try { await loadSkuNames(); }
  catch (error) { ElMessage.error(api.isAxiosError(error) ? error.response?.data?.message ?? "Name列表加载失败" : "Name列表加载失败"); }
  finally { skuNamesBusy.value = false; }
}
function normalizeNameEan(row: SkuNameDraft, value: string) {
  row.ean = String(value ?? "").replace(/\D/g, "").slice(0, 13);
  row.eanBarcodeDataUrl = undefined;
}
async function previewNameBarcode(row: SkuNameDraft) {
  if (!row.ean) { row.eanBarcodeDataUrl = undefined; return; }
  row.barcodeLoading = true;
  try {
    row.eanBarcodeDataUrl = (await api.get(`/api/production/barcodes/ean/${row.ean}`)).data.data.dataUrl;
  } catch (error) {
    row.eanBarcodeDataUrl = undefined;
    ElMessage.warning(api.isAxiosError(error) ? error.response?.data?.message ?? "EAN无法生成条码" : "EAN无法生成条码");
  } finally { row.barcodeLoading = false; }
}
async function saveSkuNames() {
  if (!selectedSku.value || skuNamesBusy.value) return;
  if (!skuNames.value.length) { ElMessage.warning("请至少添加一个Name"); return; }
  skuNamesBusy.value = true;
  try {
    await api.put(`/api/production/skus/${selectedSku.value.id}/names`, {
      names: skuNames.value.map(({ id, name, ean, sn, enabled }) => ({ id, name, ean, sn, enabled })),
    });
    await loadSkuNames();
    skus.value = (await api.get("/api/production/skus")).data.data;
    ElMessage.success("Name列表已保存");
  } catch (error) {
    ElMessage.error(api.isAxiosError(error) ? error.response?.data?.message ?? "Name列表保存失败" : "Name列表保存失败");
  } finally { skuNamesBusy.value = false; }
}
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
  inventorySkuId = ref<number | undefined>(props.initialSkuId),
  warehouses = ref<any[]>([]);
const taskSkuId = ref<number | undefined>(props.initialSkuId), taskStatus = ref(''), taskDates = ref<string[]>([]);
const locationId = ref<number>(), locations = ref<any[]>([]);
async function warehouseChanged() {
  locationId.value=undefined;
  locations.value=warehouseId.value ? (await api.get(`/api/master/warehouses/${warehouseId.value}/locations`)).data.data : [];
  await load();
}
const skuImages = ref<any[]>([]);
const skuImagesBusy = ref(false);
const skuBusy = computed(() => loading.value || skuImagesBusy.value);
type SkuImageUploadControl = { hasPending: () => boolean };
const skuImageUpload = ref<SkuImageUploadControl>();
const skuEditorSessionKey = ref(0);
const skuImageDraftToken = ref('');
const createdSkuCodePending = ref('');
async function viewSkuImages(id: number) {
  await perform(async () => {
    const images = (await api.get(`/api/production/skus/${id}/images`)).data.data;
    if (!images.length) { ElMessage.info('该 SKU 暂无商品图片'); return; }
    await loadImage(images[0].id);
  });
}
const states: Record<string, string> = {
  OPEN: "作业中 / 未处理",
  RELEASED: "已下达",
  PAUSED: "已暂停",
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
  readError.value = '';
  try {
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
      ? "work-orders"
      : tab.value === "issues"
        ? "issues"
        : tab.value === "prints"
          ? "prints"
          : tab.value === "scan-failures" ? "scan-failures" : null;
  if (path) {
    const result = (
      await api.get(`/api/production/${path}`, {
        params: { keyword: keyword.value || undefined, page: page.value, ...(path==='work-orders' ? {skuId:taskSkuId.value,status:taskStatus.value||undefined,fromDate:taskDates.value?.[0],toDate:taskDates.value?.[1]} : {}) },
      })
    ).data.data;
    total.value = result.total;
    if (path === "work-orders") tasks.value = result.records;
    if (path === "issues") issues.value = result.records;
    if (path === "prints") prints.value = result.records;
    if (path === "scan-failures") scanFailures.value = result.records;
  } else if (tab.value === 'skus') {
    skus.value = (await api.get("/api/production/skus")).data.data;
    templates.value = (await api.get("/api/production/templates")).data.data;
  } else if (tab.value === 'templates') {
    templates.value = (await api.get("/api/production/templates")).data.data;
  }
  } catch (cause) {
    readError.value = api.isAxiosError(cause) ? cause.response?.data?.message ?? '生产数据加载失败' : '生产数据加载失败';
    throw cause;
  }
}
function editSku(row?: any) {
  editingId.value = row?.id;
  createdSkuCodePending.value = '';
  skuImages.value = [];
  skuImageDraftToken.value = row ? '' : createUuid();
  skuEditorSessionKey.value++;
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
          category: "",
          batteryDescription: "",
          packageQuantity: undefined,
          cartonSizeCm: "",
          grossWeightKg: undefined,
          netWeightKg: undefined,
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
async function discardSkuImageDraft() {
  const draftToken = skuImageDraftToken.value;
  skuImageDraftToken.value = '';
  if (!draftToken) return;
  try { await api.delete(`/api/production/sku-image-drafts/${draftToken}`); }
  catch { /* 未保存草稿由服务端定时清理，关闭弹窗不阻塞用户。 */ }
}
async function saveSku() {
  if (skuImagesBusy.value) return;
  if (skuImageUpload.value?.hasPending()) { ElMessage.warning('商品图片尚未上传成功，请重试后再保存'); return; }
  await perform(async () => {
    const creating = !editingId.value;
    const createdNow = creating && !createdSkuCodePending.value;
    if (editingId.value) await api.put(`/api/production/skus/${editingId.value}`, sku);
    else if (createdNow) {
      await api.post('/api/production/skus', sku, {
        params: { imageDraftToken: skuImages.value.length ? skuImageDraftToken.value : undefined },
      });
      skuImageDraftToken.value = '';
      createdSkuCodePending.value = sku.skuCode.trim();
    }
    let savedSkuId = editingId.value;
    if (creating) {
      skus.value = (await api.get('/api/production/skus')).data.data;
      savedSkuId = skus.value.find(item => item.skuCode === createdSkuCodePending.value)?.id;
      if (!savedSkuId) throw new Error('SKU已创建，但未能确认新记录，请刷新列表后重试图片上传');
      editingId.value = savedSkuId;
      createdSkuCodePending.value = '';
      if (!createdNow) await api.put(`/api/production/skus/${savedSkuId}`, sku);
    }
    if (createTaskVisible.value) taskDraft.skuId = savedSkuId;
    skuVisible.value = false;
    await load();
    ElMessage.success(skuImages.value.length ? "SKU和商品图片已保存" : "SKU已保存");
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
async function viewTask(id: string | number, taskPage = 1) {
  await perform(async () => {
    detail.value = (await api.get(`/api/production/work-orders/${id}`, { params: { taskPage } })).data.data;
    detailTaskPage.value = taskPage;
  });
}
async function changeWorkOrderStatus(row: any, action: 'pause' | 'resume') {
  try {
    const prompt = await ElMessageBox.prompt(
      action === 'pause' ? '暂停后手持设备不能继续扫码，请填写原因' : '恢复后手持设备可继续扫码，请填写原因',
      action === 'pause' ? '暂停生产工单' : '恢复生产工单',
      { inputValidator: value => !!value?.trim() || '原因不能为空' },
    );
    await perform(async () => {
      await api.post(`/api/production/work-orders/${row.id}/${action}`, { reason: prompt.value.trim() });
      await load();
      ElMessage.success(action === 'pause' ? '生产工单已暂停' : '生产工单已恢复');
    });
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error('操作未完成');
  }
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
  await perform(() => loadImage(id));
}
async function loadImage(id: string) {
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
    if (['tasks', 'inventory'].includes(tab.value)) skus.value = (await api.get("/api/production/skus")).data.data;
    // 车型清单仅用于有生产管理权限的岗位编辑 SKU。
    if (tab.value === 'skus' && props.canManage) models.value = (await api.get("/api/master/models")).data.data;
    await load();
  }),
);
</script>

<template>
  <section class="production-manager sm-page sm-production" :class="{ 'is-overview': isOverview, 'is-module': !isOverview }">
    <ProductionOrderWorkbench v-if="detail" :key="detail.id" :work-order-id="Number(detail.id)" :context="context ?? null" :print-refresh-key="printRefreshKey" @close="detail = undefined" @changed="refreshWorkOrderList" @preview="preview" @inspect="openVehicleJourney" />
    <div v-show="!detail" class="production-list-view">
    <template v-if="isOverview">
      <ProductionOverview :tasks="tasks" :total="total" :loading="loading" :error="readError" :can-create="canManage" @detail="viewTask" @create="openCreateTask" @refresh="queryTasks" @more="scrollToTaskList" @issues="showIssueRecords" />
      <AlertCard v-if="readError" tone="error" title="生产数据加载失败" :description="readError"><template #action><el-button size="small" @click="queryTasks">重试</el-button></template></AlertCard>
    </template>
    <div v-else class="production-data-card">
    <SectionHeader :title="tab === 'tasks' ? '生产单列表' : productionTabs.find(item => item.name === tab)?.label ?? '业务列表'" />
    <div v-if="tab === 'tasks'" class="toolbar production-toolbar task-toolbar">
      <div class="task-filters">
        <el-select v-model="taskSkuId" class="task-sku-filter" clearable aria-label="商品规格筛选" placeholder="所有商品规格（SKU）" @change="queryTasks"><template #prefix><AppIcon name="inventory" /></template><el-option v-for="s in skus" :key="s.id" :value="s.id" :label="s.skuCode" /></el-select>
        <el-select v-model="taskStatus" class="task-status-filter" clearable aria-label="工单状态筛选" placeholder="所有状态" @change="queryTasks"><template #prefix><AppIcon name="layers" /></template><el-option label="已下达" value="RELEASED"/><el-option label="已暂停" value="PAUSED"/><el-option label="已完成" value="COMPLETED"/></el-select>
        <el-input v-model="keyword" class="production-search" clearable aria-label="搜索工单、编码或批次" placeholder="搜索工单、编码或批次" @keyup.enter="queryTasks" @clear="queryTasks"><template #prefix><AppIcon name="search" /></template></el-input>
        <div class="production-date-filter"><span>工单日期</span><el-date-picker v-model="taskDates" aria-label="工单创建日期范围" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" @change="queryTasks" /></div>
      </div>
      <div class="task-actions">
        <el-button @click="taskSkuId = undefined; taskStatus = ''; taskDates = []; keyword = ''; queryTasks()">重置筛选</el-button>
        <el-button class="production-refresh" @click="queryTasks"><AppIcon name="refresh" />刷新</el-button>
        <el-button v-if="canManage" type="primary" class="create-task-button" @click="openCreateTask"><AppIcon name="plus" />新建生产单</el-button>
      </div>
    </div>
    <div v-else class="toolbar production-toolbar" :class="{ 'sku-toolbar': tab === 'skus', 'inventory-toolbar': tab === 'inventory' }">
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
      <el-input v-if="['issues', 'quarantine', 'scan-failures'].includes(tab)" v-model="keyword" class="production-search" clearable aria-label="搜索工单、编码或批次" placeholder="搜索工单、编码或批次" @keyup.enter="queryTasks" @clear="queryTasks"><template #prefix><AppIcon name="search" /></template></el-input>
      <el-input v-if="tab === 'skus'" v-model="skuSearch" class="sku-search" clearable aria-label="搜索 SKU、MODEL、标题或品类" placeholder="搜索 SKU、MODEL、标题或品类"><template #prefix><AppIcon name="search" /></template></el-input>
      <el-button class="production-refresh" @click="queryTasks"><AppIcon name="refresh" />刷新</el-button>
      <el-button v-if="tab === 'skus' && canManage" class="sku-import-button" @click="skuImportVisible = true"><AppIcon name="download" />导入 SKU</el-button>
      <el-button v-if="tab === 'skus' && canManage" type="primary" @click="editSku()"><AppIcon name="plus" />新增 SKU</el-button>
    </div>

    <template v-if="tab === 'inventory'">
      <DataTable :loading="loading" :error="readError" :rows="inventoryRows" :max-height="moduleTableMaxHeight"
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
      /></DataTable>
      <p>
        包含全部已入库车辆，旧车标记“历史未关联”；隔离车辆不能流转。车型锁定数量请在库存管理查看。
      </p></template
    >
    <DataTable :loading="loading" :error="readError" v-if="tab === 'quarantine'" :rows="quarantinedParts" :max-height="moduleTableMaxHeight"
      ><el-table-column prop="code" label="隔离旧件编码" /><el-table-column
        prop="codeType"
        label="类型"
      /><el-table-column prop="qcStatus" label="质检状态" /><el-table-column label="创建时间" min-width="165"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column><el-table-column label="修改时间" min-width="165"><template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template></el-table-column><el-table-column
        v-if="canManage"
        label="操作"
        ><template #default="s"
          ><el-button link type="primary" @click="releasePart(s.row)"
            >复检合格放行</el-button
          ></template
        ></el-table-column
      ></DataTable
    >
    <DataTable :loading="loading" :error="readError" v-if="tab === 'scan-failures'" :rows="scanFailures" :max-height="moduleTableMaxHeight">
      <el-table-column label="创建时间" min-width="170"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
      <el-table-column label="修改时间" min-width="170"><template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template></el-table-column>
      <el-table-column prop="taskId" label="任务" min-width="210" />
      <el-table-column prop="code" label="输入编码" min-width="160" />
      <el-table-column prop="codeType" label="类型" />
      <el-table-column prop="operatorName" label="操作人" />
      <el-table-column prop="errorCode" label="失败代码" min-width="170" />
      <el-table-column prop="reason" label="原因" min-width="220" />
    </DataTable>
    <template v-if="tab === 'tasks'">
      <p class="task-list-hint"><AppIcon name="info" />一个工单关联一个 SKU、一个批次和多台车；首次扫码时自动创建单车记录，进入工单即可连续扫码、打印和入库。</p>
      <DataTable :loading="loading" :error="readError" :rows="tasks" class="production-task-table" scrollbar-always-on stripe :max-height="moduleTableMaxHeight" row-key="id" empty-title="暂无生产工单">
        <el-table-column prop="workOrderNo" label="工单号" min-width="150" show-overflow-tooltip />
        <el-table-column prop="skuCode" label="商品规格（SKU）" min-width="100" show-overflow-tooltip>
          <template #header><el-tooltip content="SKU是商品规格编号，用来区分同一车型的颜色、电池容量等配置。"><span>商品规格（SKU）</span></el-tooltip></template>
        </el-table-column>
        <el-table-column prop="batchNo" label="批次" min-width="110" show-overflow-tooltip />
        <el-table-column prop="plannedQuantity" label="计划" width="72" />
        <el-table-column prop="completedQuantity" label="已完成" width="78" />
        <el-table-column prop="inProgressQuantity" label="作业中" width="72" />
        <el-table-column prop="issueQuantity" label="异常" width="65" />
        <el-table-column label="状态" width="88"><template #default="{ row }"><StatusTag :label="states[row.status] ?? row.status" :tone="row.status === 'COMPLETED' ? 'green' : row.status === 'RELEASED' ? 'blue' : 'muted'" /></template></el-table-column>
        <el-table-column label="创建时间 ↓" width="165"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
        <el-table-column label="修改时间" width="165"><template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template></el-table-column>
        <el-table-column prop="createdBy" label="创建人" width="70" show-overflow-tooltip />
        <el-table-column label="操作" width="185" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="viewTask(row.id)">工单办理</el-button><el-button v-if="canManage && row.status === 'RELEASED'" link @click="changeWorkOrderStatus(row, 'pause')">暂停</el-button><el-button v-if="canManage && row.status === 'PAUSED'" link @click="changeWorkOrderStatus(row, 'resume')">恢复</el-button></template></el-table-column>
      </DataTable>
    </template>
    <template v-if="tab === 'skus'">
      <DataTable :loading="loading" :error="readError" :rows="pagedSkus" class="production-sku-table" scrollbar-always-on row-key="id" :max-height="moduleTableMaxHeight" :empty-title="skuSearch ? '没有匹配的商品 SKU' : '暂无商品 SKU'">
        <el-table-column prop="productName" label="标签标题" min-width="180" show-overflow-tooltip />
        <el-table-column prop="modelCode" label="MODEL" min-width="120" show-overflow-tooltip />
        <el-table-column prop="category" label="CATEGORY" min-width="100"><template #default="{ row }">{{ row.category || '—' }}</template></el-table-column>
        <el-table-column prop="batteryDescription" label="BATTERY" min-width="145" show-overflow-tooltip><template #default="{ row }">{{ row.batteryDescription || '—' }}</template></el-table-column>
        <el-table-column prop="packageQuantity" label="QUANTITY" width="100"><template #default="{ row }">{{ row.packageQuantity ? `${row.packageQuantity} PCS` : '—' }}</template></el-table-column>
        <el-table-column prop="cartonSizeCm" label="CARTON SIZE" min-width="125"><template #default="{ row }">{{ row.cartonSizeCm ? `${row.cartonSizeCm} CM` : '—' }}</template></el-table-column>
        <el-table-column prop="grossWeightKg" label="G.W." width="90"><template #default="{ row }">{{ row.grossWeightKg ? `${row.grossWeightKg} KG` : '—' }}</template></el-table-column>
        <el-table-column prop="netWeightKg" label="N.W." width="90"><template #default="{ row }">{{ row.netWeightKg ? `${row.netWeightKg} KG` : '—' }}</template></el-table-column>
        <el-table-column prop="nameCount" label="Name" width="80"><template #default="{ row }">{{ row.nameCount ?? 0 }}</template></el-table-column>
        <el-table-column label="状态" width="80"><template #default="{ row }"><span class="sku-status" :class="{ disabled: !row.enabled }">{{ row.enabled ? '启用' : '停用' }}</span></template></el-table-column>
        <el-table-column label="创建时间 ↓" min-width="165"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
        <el-table-column label="修改时间" min-width="165"><template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template></el-table-column>
        <el-table-column label="操作" width="310" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="continueWithSku('tasks', row)">安排生产</el-button><el-button link type="primary" @click="openSkuNames(row)">Name 列表</el-button><el-button link type="primary" @click="viewSkuImages(row.id)">商品图片</el-button><el-button v-if="canManage" link type="primary" @click="editSku(row)">编辑</el-button></template></el-table-column>
      </DataTable>
      <Pagination v-model:page="skuPage" v-model:page-size="skuPageSize" :sizes="[20, 50, 100]" :total="filteredSkus.length" :disabled="loading" />
    </template>
    <DataTable :loading="loading" :error="readError" v-if="tab === 'templates'" :rows="templates" :max-height="moduleTableMaxHeight"
      ><el-table-column prop="name" label="标签模板" /><el-table-column
        prop="widthMm"
        label="宽 mm"
      /><el-table-column prop="heightMm" label="高 mm" /><el-table-column
        prop="heading"
        label="标题"
      /><el-table-column prop="version" label="版本" /><el-table-column label="创建时间 ↓" min-width="165"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column><el-table-column label="修改时间" min-width="165"><template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template></el-table-column><el-table-column
        v-if="canManage"
        label="操作"
        ><template #default="s"
          ><el-button link type="primary" @click="editTemplate(s.row)"
            >配置版式</el-button
          ></template
        ></el-table-column
      ></DataTable
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
      <DataTable :loading="loading" :error="readError" :rows="issues" :max-height="moduleTableMaxHeight"
        ><el-table-column label="聚合码 / 部件码" min-width="200" show-overflow-tooltip><template #default="{ row }">{{ row.vehicleQrCode || row.code }}</template></el-table-column><el-table-column
          prop="reason"
          label="异常原因"
        /><el-table-column label="状态"
          ><template #default="s">{{
            states[s.row.status]
          }}</template></el-table-column
        ><el-table-column prop="createdBy" label="登记人" /><el-table-column label="创建时间 ↓" min-width="165"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column><el-table-column label="修改时间" min-width="165"><template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template></el-table-column><el-table-column
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
        ></DataTable
      >
    </template>
    <template v-if="tab === 'prints'"
      ><el-alert
        title="自动打印终端领取待打印任务。已提交表示送入打印队列；超时未回报需核对实物后补打。"
        type="info"
        :closable="false"
      /><DataTable :loading="loading" :error="readError" :rows="prints" :max-height="moduleTableMaxHeight"
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
        /><el-table-column prop="message" label="结果" /><el-table-column label="创建时间 ↓" min-width="165"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column><el-table-column label="修改时间" min-width="165"><template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template></el-table-column><el-table-column
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
        ></DataTable
      ></template
    >
    <Pagination v-if="['tasks', 'issues', 'prints', 'quarantine', 'scan-failures'].includes(tab)" v-model:page="page" :total="total" :page-size="50" :disabled="loading" jumper @change="perform(load)" />
    </div>
    </div>
    <SkuImportDialog v-if="canManage" v-model="skuImportVisible" @imported="refreshSkusAfterImport" />
    <el-dialog v-model="createTaskVisible" title="创建生产单" width="520px" :close-on-click-modal="false" :close-on-press-escape="!loading" :show-close="!loading">
      <p class="production-dialog-help">一个工单只关联一次商品规格、批次和计划数量。现场每台车在首次扫码时自动生成单车记录。</p>
      <el-alert v-if="pendingTask" title="上次请求未确认，请重试原工单，系统会防止重复创建。" type="info" :closable="false" />
      <el-form label-position="top" @submit.prevent="saveTask">
        <el-form-item label="商品规格（SKU）" required><el-select v-model="taskDraft.skuId" :disabled="loading || !!pendingTask" filterable placeholder="请选择商品规格"><el-option v-for="s in skus.filter(item => item.enabled)" :key="s.id" :value="s.id" :label="s.skuCode + ' · ' + s.productName" /></el-select><el-button link type="primary" :disabled="loading || !!pendingTask" @click="prepareWorkOrderSku">没有需要的规格？在这里新增商品</el-button></el-form-item>
        <el-form-item label="生产批次" required><el-input v-model="taskDraft.batchNo" :disabled="loading || !!pendingTask" maxlength="64" placeholder="同一批量生产使用统一批次" /></el-form-item>
        <el-form-item label="计划数量" required><el-input-number v-model="taskDraft.plannedQuantity" :disabled="loading || !!pendingTask" :min="1" :max="1000000" controls-position="right" /></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="loading" @click="createTaskVisible = false">取消</el-button><el-button type="primary" :loading="loading" @click="saveTask">{{ pendingTask ? '重试创建' : '创建并开始生产' }}</el-button></template>
    </el-dialog>
    <el-dialog v-model="skuVisible" :title="editingId ? '编辑商品 SKU' : '新增商品 SKU'" width="820px" class="sku-editor-dialog" :close-on-click-modal="false" :close-on-press-escape="!skuBusy" :show-close="!skuBusy" @closed="discardSkuImageDraft">
      <p class="sku-editor-intro">这里维护标签亮黄色区域的主信息；一个 SKU 可在 Name 列表中绑定多条 Name 明细。</p>
      <el-form class="sku-editor-form" label-position="top" :disabled="loading">
        <el-form-item label="车型" required>
          <el-select v-model="sku.modelId" :disabled="!!editingId || !!createdSkuCodePending" filterable placeholder="请选择车型">
            <el-option v-for="m in models" :key="m.id" :label="m.name" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="SKU 编码" required>
          <el-input v-model="sku.skuCode" :disabled="!!editingId || !!createdSkuCodePending" maxlength="64" placeholder="填写唯一的商品规格编号" />
        </el-form-item>
        <el-form-item label="标签标题" required><el-input v-model="sku.productName" maxlength="100" placeholder="如 TEEWING Flux One A" /></el-form-item>
        <el-form-item label="规格"><el-input v-model="sku.specification" maxlength="100" placeholder="如颜色、电池容量等" /></el-form-item>
        <el-form-item label="CATEGORY"><el-input v-model="sku.category" maxlength="64" placeholder="如 EBIKE、Frameset" /></el-form-item>
        <el-form-item label="BATTERY"><el-input v-model="sku.batteryDescription" maxlength="100" placeholder="如 Battery included" /></el-form-item>
        <el-form-item label="QUANTITY"><el-input-number v-model="sku.packageQuantity" :min="1" :max="10000" controls-position="right" /><span class="sku-field-unit">PCS</span></el-form-item>
        <el-form-item label="CARTON SIZE"><el-input v-model="sku.cartonSizeCm" maxlength="64" placeholder="如 173.5*26.5*89"><template #append>CM</template></el-input></el-form-item>
        <el-form-item label="G.W."><el-input-number v-model="sku.grossWeightKg" :min="0.001" :precision="3" :step="0.1" controls-position="right" /><span class="sku-field-unit">KG</span></el-form-item>
        <el-form-item label="N.W."><el-input-number v-model="sku.netWeightKg" :min="0.001" :precision="3" :step="0.1" controls-position="right" /><span class="sku-field-unit">KG</span></el-form-item>
        <el-form-item label="标签模板" required>
          <el-select v-model="sku.templateId"><el-option v-for="t in templates" :key="t.id" :label="t.name" :value="t.id" /></el-select>
        </el-form-item>
        <el-form-item label="状态"><el-switch v-model="sku.enabled" aria-label="启用商品 SKU" /><span class="sku-enabled-label">{{ sku.enabled ? '启用' : '停用' }}</span></el-form-item>
      </el-form>
      <section class="sku-editor-images" aria-label="商品图片管理">
        <h3>商品图片</h3>
        <div v-if="skuImages.length" class="sku-image-links">
          <el-button v-for="(a, index) in skuImages" :key="a.id" :disabled="skuBusy" @click="showImage(a.id)">查看图片 {{ index + 1 }}</el-button>
        </div>
        <BusinessAttachmentUpload ref="skuImageUpload" v-if="skuVisible" :key="skuEditorSessionKey" :endpoint="editingId ? `/api/production/skus/${editingId}/images` : `/api/production/sku-image-drafts/${skuImageDraftToken}`" images-only auto-upload :disabled="loading || (!!createdSkuCodePending && !editingId)" @saved="skuImageSaved" @busy="skuImagesBusy = $event" />
      </section>
      <template #footer>
        <el-button :disabled="skuBusy" @click="skuVisible = false">取消</el-button>
        <el-button type="primary" :loading="loading" :disabled="skuImagesBusy" @click="saveSku">保存</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="skuNamesVisible" :title="`Name 列表 · ${selectedSku?.modelCode ?? ''}`" width="min(1120px, 96vw)" class="sku-name-dialog" :close-on-click-modal="false" :close-on-press-escape="!skuNamesBusy" :show-close="!skuNamesBusy">
      <div class="sku-name-summary">
        <div><span>MODEL</span><strong>{{ selectedSku?.modelCode || '—' }}</strong></div>
        <div><span>标签标题</span><strong>{{ selectedSku?.productName || '—' }}</strong></div>
        <el-button v-if="canManage" type="primary" plain :disabled="skuNamesBusy" @click="addSkuName"><AppIcon name="plus" />添加 Name</el-button>
      </div>
      <DataTable :loading="skuNamesBusy" :rows="skuNames" row-key="clientKey" empty-title="尚未绑定 Name">
        <el-table-column label="NAME" min-width="235">
          <template #default="{ row }"><el-input v-model="row.name" :disabled="!canManage" maxlength="128" placeholder="填写唯一 Name" /></template>
        </el-table-column>
        <el-table-column label="EAN" min-width="180">
          <template #default="{ row }"><el-input :model-value="row.ean" :disabled="!canManage" inputmode="numeric" maxlength="13" placeholder="13位数字" @input="normalizeNameEan(row, $event)" @blur="previewNameBarcode(row)" /></template>
        </el-table-column>
        <el-table-column label="一维码" min-width="210">
          <template #default="{ row }"><div class="ean-barcode-preview" v-loading="row.barcodeLoading"><img v-if="row.eanBarcodeDataUrl" :src="row.eanBarcodeDataUrl" :alt="`${row.ean} 一维码`" /><span v-else>{{ row.ean ? '输入完成后自动生成' : '—' }}</span></div></template>
        </el-table-column>
        <el-table-column label="S/N" min-width="180">
          <template #default="{ row }"><el-input v-model="row.sn" :disabled="!canManage" maxlength="128" placeholder="填写唯一 SN" /></template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-switch v-model="row.enabled" :disabled="!canManage" aria-label="启用 Name" /></template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="165"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
        <el-table-column label="修改时间" min-width="165"><template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template></el-table-column>
        <el-table-column v-if="canManage" label="操作" width="75" fixed="right">
          <template #default="{ row }"><el-button v-if="!row.id" link type="danger" @click="removeUnsavedSkuName(row)">移除</el-button></template>
        </el-table-column>
      </DataTable>
      <template #footer><el-button :disabled="skuNamesBusy" @click="skuNamesVisible = false">关闭</el-button><el-button v-if="canManage" type="primary" :loading="skuNamesBusy" @click="saveSkuNames">保存全部</el-button></template>
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
    <el-dialog :model-value="!!vehicleJourney" title="单车办理 · 打印与库存流转" width="min(1080px,96vw)" @close="vehicleJourney = undefined; journeyVersion++">
      <div v-loading="journeyBusy" v-if="vehicleJourney">
        <p>车架：{{ vehicleJourney.frameSn || '—' }} · 总码：{{ vehicleJourney.qrCode || '四码未完成' }}</p>
        <el-alert v-if="journeyError" type="error" :title="journeyError" :closable="false" /><el-button v-if="journeyError" @click="openVehicleJourney(vehicleJourney)">重试关联记录</el-button>
        <h3>本车打印记录</h3><el-table :data="vehiclePrints" empty-text="暂无打印任务；四码完成后自动生成首次打印任务"><el-table-column prop="id" label="任务号" min-width="180" /><el-table-column label="状态" min-width="100"><template #default="{row}">{{ states[row.status] ?? row.status }}</template></el-table-column><el-table-column label="创建时间 ↓" min-width="165"><template #default="{row}">{{ formatDateTime(row.createdAt) }}</template></el-table-column><el-table-column label="修改时间" min-width="165"><template #default="{row}">{{ formatDateTime(row.updatedAt) }}</template></el-table-column><el-table-column label="操作" width="130"><template #default="{row}"><el-button link type="primary" @click="preview(row.id)">预览 / 打印</el-button></template></el-table-column></el-table>
        <p>打印提交后请核对实物标签；返回生产单的「办理入库」即可继续，也可由入库人员使用 PDA 扫码办理。</p>
        <el-button v-if="vehicleTrace?.vehicle.currentOrderNo && hasPermission(context,'ORDER_READ')" type="primary" @click="emit('order',String(vehicleTrace.vehicle.currentOrderNo))">打开本车关联订单 {{ vehicleTrace.vehicle.currentOrderNo }}</el-button>
        <TraceDetails v-if="vehicleTrace" :data="vehicleTrace" />
        <p v-else-if="vehicleJourney.qrCode && !hasPermission(context,'TRACE_READ')">当前账号没有追溯权限，库存及出入库履历请由有权限的人员查看。</p>
      </div>
    </el-dialog>
    <el-dialog v-model="previewVisible" title="外箱标签预览" @closed="printRefreshKey++"
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
.production-list-view { display: grid; gap: 18px; min-width: 0; }
.toolbar {
  display: flex;
  gap: 10px;
  margin: 12px 0;
}
.toolbar .el-input {
  max-width: 340px;
}
.production-manager .production-toolbar.task-toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
  gap: 16px 24px;
}
.task-filters {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(0, .75fr) minmax(0, 1.3fr);
  gap: 12px;
  min-width: 0;
}
.task-filters > .el-select,
.task-filters > .el-input {
  width: 100%;
  min-width: 0;
  max-width: none;
}
.task-filters .production-date-filter {
  grid-column: 1 / -1;
  margin: 0;
  flex-wrap: nowrap;
}
.task-filters .production-date-filter > span {
  flex-shrink: 0;
}
.task-filters .production-date-filter :deep(.el-date-editor) {
  min-width: 0;
  min-height: 35px;
}
.task-actions {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
}
.task-actions .el-button {
  height: 35px;
  margin: 0;
  padding: 0 14px;
}
@media (max-width: 1200px) {
  .production-manager .production-toolbar.task-toolbar {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 600px) {
  .task-filters {
    grid-template-columns: minmax(0, 1fr);
  }
  .task-filters .production-date-filter {
    align-items: stretch;
    flex-direction: column;
    gap: 8px;
  }
  .task-filters .production-date-filter :deep(.el-date-editor) {
    flex: none;
    width: 100%;
  }
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
