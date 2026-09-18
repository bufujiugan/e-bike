<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted } from "vue";
import api from "../api";
import { ElMessage, ElMessageBox } from "element-plus";
const props = defineProps<{
  canManage: boolean;
  canOperate?: boolean;
  canReportIssue?: boolean;
}>();
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
const skuVisible = ref(false),
  templateVisible = ref(false),
  editingId = ref<number>(),
  detail = ref<any>(),
  labelHtml = ref(""),
  previewVisible = ref(false);
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
  issueImage = ref<File>(),
  lastIssueId = ref(""),
  imageUrl = ref("");
const quarantinedParts = ref<any[]>([]);
const inventoryRows = ref<any[]>([]),
  warehouseId = ref<number>(),
  inventorySkuId = ref<number>(),
  warehouses = ref<any[]>([]),
  importFile = ref<File>(),
  importResult = ref<any>();
async function downloadTemplate() {
  await perform(async () => {
    const blob = (
      await api.get("/api/production/skus/import-template", {
        responseType: "blob",
      })
    ).data;
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "sku-template.xlsx";
    a.click();
    URL.revokeObjectURL(url);
  });
}
async function importSkus() {
  if (!importFile.value) return;
  await perform(async () => {
    const form = new FormData();
    form.append("file", importFile.value!);
    importResult.value = (
      await api.post("/api/production/skus/import", form)
    ).data.data;
    await load();
  });
}
const skuImages = ref<any[]>([]),
  skuImageFile = ref<File>();
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
async function perform(action: () => Promise<void>) {
  if (loading.value) return;
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
  }
}
async function load() {
  if (tab.value === "inventory") {
    inventoryRows.value = (
      await api.get("/api/production/inventory", {
        params: { skuId: inventorySkuId.value, warehouseId: warehouseId.value },
      })
    ).data.data;
    warehouses.value = (await api.get("/api/master/warehouses")).data.data;
    return;
  }
  if (tab.value === "quarantine") {
    quarantinedParts.value = (
      await api.get("/api/production/quarantined-parts")
    ).data.data;
    return;
  }
  const path =
    tab.value === "tasks"
      ? "tasks"
      : tab.value === "issues"
        ? "issues"
        : tab.value === "prints"
          ? "prints"
          : null;
  if (path) {
    const result = (
      await api.get(`/api/production/${path}`, {
        params: { keyword: keyword.value || undefined, page: page.value },
      })
    ).data.data;
    total.value = result.total;
    if (path === "tasks") tasks.value = result.records;
    if (path === "issues") issues.value = result.records;
    if (path === "prints") prints.value = result.records;
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
  skuImageFile.value = undefined;
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
async function uploadSkuImage() {
  if (!editingId.value || !skuImageFile.value) return;
  await perform(async () => {
    const form = new FormData();
    form.append("file", skuImageFile.value!);
    await api.post(`/api/production/skus/${editingId.value}/images`, form);
    skuImages.value = (
      await api.get(`/api/production/skus/${editingId.value}/images`)
    ).data.data;
    skuImageFile.value = undefined;
  });
}
async function saveSku() {
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
    labelHtml.value = (
      await api.get(`/api/production/prints/${id}/label`, {
        responseType: "text",
      })
    ).data;
    previewVisible.value = true;
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
  await perform(async () => {
    lastIssueId.value = (
      await api.post("/api/production/issues", issue)
    ).data.data.id;
    await upload();
    issue.code = "";
    issue.reason = "";
    await load();
    ElMessage.success("异常已登记并隔离");
  });
}
async function upload() {
  if (!issueImage.value || !lastIssueId.value) return;
  const form = new FormData();
  form.append("file", issueImage.value);
  await api.post(`/api/production/issues/${lastIssueId.value}/images`, form);
  issueImage.value = undefined;
}
async function showImage(id: string) {
  await perform(async () => {
    if (imageUrl.value) URL.revokeObjectURL(imageUrl.value);
    imageUrl.value = URL.createObjectURL(
      (await api.get(`/api/production/images/${id}`, { responseType: "blob" }))
        .data,
    );
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
onUnmounted(() => {
  if (imageUrl.value) URL.revokeObjectURL(imageUrl.value);
});
onMounted(() =>
  perform(async () => {
    skus.value = (await api.get("/api/production/skus")).data.data;
    templates.value = (await api.get("/api/production/templates")).data.data;
    models.value = (await api.get("/api/master/models")).data.data;
    await load();
  }),
);
</script>

<template>
  <section class="production-manager" v-loading="loading">
    <header>
      <div>
        <h2>生产中心</h2>
        <p>四码聚合 · 作业交接 · 标签打印 · 质量隔离</p>
      </div>
    </header>
    <el-tabs v-model="tab" @tab-change="changeTab"
      ><el-tab-pane label="生产任务" name="tasks" /><el-tab-pane
        label="商品 SKU"
        name="skus" /><el-tab-pane
        label="标签模板"
        name="templates" /><el-tab-pane
        label="异常隔离"
        name="issues" /><el-tab-pane
        label="旧件复检"
        name="quarantine" /><el-tab-pane
        label="打印队列"
        name="prints" /><el-tab-pane label="生产库存" name="inventory"
    /></el-tabs>
    <template v-if="tab === 'inventory'"
      ><div class="toolbar">
        <el-select
          v-model="inventorySkuId"
          clearable
          placeholder="所有SKU"
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
          @change="perform(load)"
          ><el-option
            v-for="w in warehouses"
            :key="w.id"
            :value="w.id"
            :label="w.name"
        /></el-select>
      </div>
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
        显示通过生产任务建立的整车；销售锁定与订单占用将在 PI 阶段接入。
      </p></template
    >
    <div v-if="tab === 'skus' && canManage" class="toolbar">
      <el-button @click="downloadTemplate">下载导入模板</el-button
      ><input
        type="file"
        accept=".xlsx,.xls"
        @change="importFile = ($event.target as HTMLInputElement).files?.[0]"
      /><el-button :disabled="!importFile" @click="importSkus"
        >导入SKU</el-button
      >
    </div>
    <el-alert
      v-if="tab === 'skus' && importResult"
      :title="`导入成功 ${importResult.successRows} 行，失败 ${importResult.failureRows} 行`"
      :description="
        importResult.failures
          .map((f: any) => `第${f.rowNumber}行：${f.reason}`)
          .join('；')
      "
      :closable="false"
    />
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
    <div class="toolbar">
      <el-input
        v-if="['tasks', 'issues'].includes(tab)"
        v-model="keyword"
        placeholder="搜索任务、编码或批次"
        @keyup.enter="
          page = 1;
          perform(load);
        "
      /><el-button
        @click="
          page = 1;
          perform(load);
        "
        >刷新</el-button
      ><el-button
        v-if="tab === 'skus' && canManage"
        type="primary"
        @click="editSku()"
        >新增 SKU</el-button
      >
    </div>
    <el-table v-if="tab === 'tasks'" :data="tasks"
      ><el-table-column
        prop="id"
        label="任务号"
        min-width="290"
      /><el-table-column prop="skuCode" label="SKU" /><el-table-column
        prop="batchNo"
        label="批次"
      /><el-table-column prop="frameSn" label="车架" /><el-table-column
        label="状态"
        ><template #default="s">{{
          states[s.row.status]
        }}</template></el-table-column
      ><el-table-column prop="createdBy" label="创建人" /><el-table-column
        label="操作"
        ><template #default="s"
          ><el-button link type="primary" @click="viewTask(s.row.id)"
            >查看进度</el-button
          ></template
        ></el-table-column
      ></el-table
    >
    <el-table v-if="tab === 'skus'" :data="skus"
      ><el-table-column prop="skuCode" label="SKU" /><el-table-column
        prop="modelName"
        label="车型"
      /><el-table-column prop="productName" label="商品名称" /><el-table-column
        prop="specification"
        label="规格"
      /><el-table-column prop="ean" label="EAN" /><el-table-column
        prop="upc"
        label="UPC"
      /><el-table-column label="状态"
        ><template #default="s">{{
          s.row.enabled ? "启用" : "停用"
        }}</template></el-table-column
      ><el-table-column v-if="canManage" label="操作"
        ><template #default="s"
          ><el-button link type="primary" @click="editSku(s.row)"
            >编辑</el-button
          ></template
        ></el-table-column
      ></el-table
    >
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
          ><el-input v-model="issue.code" maxlength="128" /></el-form-item
        ><el-form-item label="异常原因"
          ><el-input v-model="issue.reason" maxlength="500" /></el-form-item
        ><el-form-item
          ><input
            type="file"
            accept="image/png,image/jpeg"
            @change="
              issueImage = ($event.target as HTMLInputElement).files?.[0]
            " /></el-form-item
        ><el-button type="danger" @click="createIssue">登记并隔离</el-button
        ><el-button v-if="lastIssueId && issueImage" @click="perform(upload)"
          >重试上传图片</el-button
        ></el-form
      >
      <el-table :data="issues"
        ><el-table-column prop="code" label="车辆/部件编码" /><el-table-column
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
    <el-pagination
      v-if="['tasks', 'issues', 'prints'].includes(tab)"
      v-model:current-page="page"
      :page-size="50"
      :total="total"
      layout="prev,pager,next,total"
      @current-change="perform(load)"
    />
    <el-dialog v-model="skuVisible" title="生产商品 SKU" width="550px">
      <div v-if="editingId" class="toolbar">
        <input
          type="file"
          accept="image/png,image/jpeg"
          @change="
            skuImageFile = ($event.target as HTMLInputElement).files?.[0]
          "
        /><el-button :disabled="!skuImageFile" @click="uploadSkuImage"
          >上传图片</el-button
        >
      </div>
      <el-button v-for="a in skuImages" :key="a.id" @click="showImage(a.id)"
        >查看图片</el-button
      >
      <p v-if="!editingId">保存商品后可上传产品图片。</p>
      <el-form label-width="100px"
        ><el-form-item label="车型"
          ><el-select v-model="sku.modelId" :disabled="!!editingId"
            ><el-option
              v-for="m in models"
              :key="m.id"
              :label="m.name"
              :value="m.id" /></el-select></el-form-item
        ><el-form-item label="SKU编码"
          ><el-input
            v-model="sku.skuCode"
            :disabled="!!editingId"
            maxlength="64" /></el-form-item
        ><el-form-item label="商品名称"
          ><el-input v-model="sku.productName" maxlength="100" /></el-form-item
        ><el-form-item label="规格"
          ><el-input
            v-model="sku.specification"
            maxlength="100" /></el-form-item
        ><el-form-item label="EAN"
          ><el-input v-model="sku.ean" maxlength="32" /></el-form-item
        ><el-form-item label="UPC"
          ><el-input v-model="sku.upc" maxlength="32" /></el-form-item
        ><el-form-item label="标签模板"
          ><el-select v-model="sku.templateId"
            ><el-option
              v-for="t in templates"
              :key="t.id"
              :label="t.name"
              :value="t.id" /></el-select></el-form-item
        ><el-form-item label="启用"
          ><el-switch v-model="sku.enabled" /></el-form-item></el-form
      ><template #footer
        ><el-button type="primary" :loading="loading" @click="saveSku"
          >保存</el-button
        ></template
      ></el-dialog
    >
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
      ><iframe
        :srcdoc="labelHtml"
        title="标签"
        sandbox="allow-same-origin allow-modals"
        style="width: 100%; height: 620px; border: 0"
    /></el-dialog>
    <el-dialog
      :model-value="!!imageUrl"
      title="生产异常图片"
      @close="imageUrl = ''"
      ><img :src="imageUrl" alt="异常证据" style="max-width: 100%"
    /></el-dialog>
  </section>
</template>
<style scoped>
.production-manager {
  margin: 24px;
  padding: 26px;
  background: #fff;
  border: 1px solid #e3e9f2;
  border-radius: 14px;
}
.production-manager header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.production-manager h2 {
  margin: 0;
}
.production-manager p {
  color: #64748b;
}
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
