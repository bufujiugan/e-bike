<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import api from '../api'
import { piApi, type PiDocument, type PiTemplate } from '../pi-api'

const props = defineProps<{ tradeType: string; document: PiDocument; canManage: boolean; disabled: boolean; refreshKey: number }>()
const emit = defineEmits<{ select: [template: PiTemplate]; manage: [] }>()
const templates = ref<PiTemplate[]>([]), page = ref(1), total = ref(0), busy = ref(false), error = ref('')
const hasSelection = computed(() => Boolean(props.document.templateCode && props.document.templateVersion))
let requestVersion = 0
async function load() {
  const version = ++requestVersion
  busy.value = true; error.value = ''
  try {
    const response = await piApi.templates(props.tradeType, page.value)
    if (version === requestVersion) { templates.value = response.records; total.value = response.total }
  } catch (cause) {
    if (version === requestVersion) {
      templates.value = []; total.value = 0
      error.value = api.isAxiosError(cause) ? cause.response?.data?.message || '模板加载失败，请重试' : '模板加载失败，请重试'
    }
  } finally { if (version === requestVersion) busy.value = false }
}
watch(() => props.tradeType, () => { page.value = 1; templates.value = []; void load() }, { immediate: true })
watch(() => props.refreshKey, () => { page.value = 1; void load() })
onBeforeUnmount(() => { requestVersion++ })
function isSelected(template: PiTemplate) {
  return template.templateCode === props.document.templateCode && template.templateVersion === props.document.templateVersion
}
</script>

<template>
  <section class="pi-template-picker" aria-label="选择单据模板">
    <div class="pi-section-heading">
      <div><h3>单据模板 <span class="pi-required">*</span></h3><p>选一次模板，自动带入卖方与收款银行，无需重复填写。</p></div>
      <div class="pi-inline-actions"><el-button text :loading="busy" :disabled="disabled" @click="load">刷新</el-button><el-button v-if="canManage" text :disabled="disabled" @click="emit('manage')">管理模板</el-button></div>
    </div>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false"><el-button text :disabled="disabled" @click="load">重新加载模板</el-button></el-alert>
    <div v-else-if="!busy && !templates.length" class="pi-template-empty">
      <strong>{{ hasSelection ? '当前查询未返回可替换的模板' : '暂无已发布的' + (tradeType === 'EXPORT' ? '外销' : '内销') + '模板' }}</strong>
      <p>{{ hasSelection ? '已保留单据中的模板快照；保存时仍由服务端校验模板有效性。' : '可先填写其他资料；保存前需要选用已发布的完整 V3 模板。' }}</p>
      <el-button v-if="canManage" :disabled="disabled" @click="emit('manage')">前往模板管理</el-button><span v-else>请联系模板管理员发布。</span>
    </div>
    <div v-loading="busy" class="pi-template-options" :aria-busy="busy">
      <button v-for="template in templates" :key="template.id" type="button" class="pi-template-option" :class="{ selected: isSelected(template) }" :aria-pressed="isSelected(template)" :disabled="disabled || busy || !template.layoutCode.endsWith('_V3')" @click="emit('select', template)">
        <span class="pi-template-option-title"><strong>{{ template.templateCode }}</strong><span>{{ template.templateVersion }}</span><b>{{ isSelected(template) ? '已选用' : template.layoutCode.endsWith('_V3') ? '选用' : '历史版式 · 不可选用' }}</b></span>
        <span>{{ template.sellerName }}</span><small>{{ template.bankName }}</small>
      </button>
    </div>
    <el-pagination v-if="total > 20" v-model:current-page="page" :disabled="disabled || busy" :page-size="20" :total="total" layout="prev, pager, next" @current-change="load" />
    <details v-if="hasSelection" class="pi-disclosure pi-template-snapshot">
      <summary><span><strong>{{ document.sellerName }}</strong><small>{{ document.templateCode }} · {{ document.templateVersion }} · 受控资料</small></span><span>查看卖方与银行资料</span></summary>
      <dl class="pi-detail-grid">
        <div><dt>卖方名称</dt><dd>{{ document.sellerName || '未记录' }}</dd></div>
        <div><dt>卖方国家 / 地区</dt><dd>{{ document.sellerCountry || '未记录' }}</dd></div>
        <div><dt>卖方地址</dt><dd>{{ document.sellerAddress || '未记录' }}</dd></div>
        <div><dt>卖方联系人</dt><dd>{{ document.sellerContactName || '未记录' }}</dd></div>
        <div><dt>卖方电话</dt><dd>{{ document.sellerPhone || '未记录' }}</dd></div>
        <div><dt>卖方 Email</dt><dd>{{ document.sellerEmail || '未记录' }}</dd></div>
        <div><dt>补充联系方式</dt><dd>{{ document.sellerContact || '未记录' }}</dd></div>
        <div><dt>收款人</dt><dd>{{ document.beneficiary || '未记录' }}</dd></div>
        <div><dt>收款银行</dt><dd>{{ document.bankName || '未记录' }}</dd></div>
        <div><dt>收款账号</dt><dd>{{ document.bankAccount || '未记录' }}</dd></div>
      </dl>
      <p class="pi-field-help">以上内容随模板固定带入。如需修改，请由模板管理员发布新版本。</p>
    </details>
  </section>
</template>
