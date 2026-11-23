<script setup lang="ts">
import { tr, trInline } from '../i18n/language'
import { isSelectablePiLayout } from '../pi-template-layouts'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import api from '../api'
import { piApi, type PiDocument, type PiTemplate } from '../pi-api'

const props = defineProps<{ tradeType: string; document: PiDocument; canManage: boolean; disabled: boolean; manageDisabled?: boolean; profileBound?: boolean; refreshKey: number; fixedLayoutName?: string }>()
const emit = defineEmits<{ select: [template: PiTemplate]; manage: []; adjust: [template: PiTemplate] }>()
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
  <section class="pi-template-picker" :aria-label='tr("选择单据模板")'>
    <div class="pi-section-heading">
      <div><h3>{{ fixedLayoutName ? tr('卖方与银行资料') : tr('单据模板') }} <span class="pi-required">*</span></h3><p v-if="fixedLayoutName">{{ trInline("单据版式固定为") }} {{ fixedLayoutName }}{{ trInline("。以下选择带入卖方与收款银行。") }}</p><p v-else>{{ tr("选一次模板，自动带入卖方与收款银行，无需重复填写。") }}</p></div>
      <div class="pi-inline-actions"><el-button text :loading="busy" :disabled="manageDisabled" @click="load">{{ tr("刷新") }}</el-button><el-button v-if="canManage" text :disabled="manageDisabled" @click="emit('manage')">{{ tr("管理模板") }}</el-button></div>
    </div>
    <p v-if="profileBound" class="pi-field-help">{{ tr("当前模板由履约配置指定。可直接调整模板；保存新版本后，需在履约配置中选用新模板，再应用到本单。") }}</p>
    <el-alert v-if="error" :title="tr(error)" type="error" show-icon :closable="false"><el-button text :disabled="disabled" @click="load">{{ tr("重新加载模板") }}</el-button></el-alert>
    <div v-else-if="!busy && !templates.length" class="pi-template-empty">
      <strong>{{ hasSelection ? tr('当前查询未返回可替换的模板') : tr('暂无已发布的') + (tradeType === 'EXPORT' ? tr('外销') : tr('内销')) + tr('模板') }}</strong>
      <p>{{ hasSelection ? tr('已保留单据中的模板快照；保存时仍由服务端校验模板有效性。') : tr('可先填写其他资料；保存前需要选用已发布的完整模板。') }}</p>
      <el-button v-if="canManage" :disabled="manageDisabled" @click="emit('manage')">{{ tr("前往模板管理") }}</el-button><span v-else>{{ tr("请联系模板管理员发布。") }}</span>
    </div>
    <div v-loading="busy" class="pi-template-options" :aria-busy="busy">
      <article v-for="template in templates" :key="template.id" class="pi-template-option" :class="{ selected: isSelected(template) }" :aria-label="`${template.templateCode} · ${template.templateVersion}`">
        <div class="pi-template-option-title"><strong>{{ template.templateCode }}</strong><span>{{ template.templateVersion }}</span><b v-if="isSelected(template)">{{ profileBound ? tr('配置指定 · 已选用') : tr('已选用') }}</b><b v-else-if="!isSelectablePiLayout(template.layoutCode)">{{ tr("历史版式 · 不可选用") }}</b></div>
        <small>{{ trInline("模板 ID：") }}{{ template.id }}</small><span>{{ template.sellerName }}</span><small>{{ template.bankName }}</small>
        <div class="pi-template-option-actions">
          <el-button v-if="canManage" plain type="primary" :disabled="manageDisabled || busy" @click="emit('adjust', template)">{{ tr("调整模板") }}</el-button>
          <el-button :disabled="disabled || busy || !isSelectablePiLayout(template.layoutCode)" :type="isSelected(template) ? 'primary' : 'default'" :aria-pressed="isSelected(template)" @click="emit('select', template)">{{ isSelected(template) ? tr('已选用') : tr('选用模板') }}</el-button>
        </div>
      </article>
    </div>
    <el-pagination v-if="total > 20" v-model:current-page="page" :disabled="disabled || busy" :page-size="20" :total="total" layout="prev, pager, next" @current-change="load" />
    <details v-if="hasSelection" class="pi-disclosure pi-template-snapshot">
      <summary><span><strong>{{ document.sellerName }}</strong><small>{{ document.templateCode }} · {{ document.templateVersion }} {{ trInline("· 受控资料") }}</small></span><span>{{ tr("查看卖方与银行资料") }}</span></summary>
      <dl class="pi-detail-grid">
        <div><dt>{{ tr("卖方名称") }}</dt><dd>{{ document.sellerName || tr('未记录') }}</dd></div>
        <div><dt>{{ tr("卖方国家 / 地区") }}</dt><dd>{{ document.sellerCountry || tr('未记录') }}</dd></div>
        <div><dt>{{ tr("卖方地址") }}</dt><dd>{{ document.sellerAddress || tr('未记录') }}</dd></div>
        <div><dt>{{ tr("卖方联系人") }}</dt><dd>{{ document.sellerContactName || tr('未记录') }}</dd></div>
        <div><dt>{{ tr("卖方电话") }}</dt><dd>{{ document.sellerPhone || tr('未记录') }}</dd></div>
        <div><dt>{{ tr("卖方 Email") }}</dt><dd>{{ document.sellerEmail || tr('未记录') }}</dd></div>
        <div><dt>{{ tr("补充联系方式") }}</dt><dd>{{ document.sellerContact || tr('未记录') }}</dd></div>
        <div><dt>{{ tr("收款人") }}</dt><dd>{{ document.beneficiary || tr('未记录') }}</dd></div>
        <div><dt>{{ tr("收款银行") }}</dt><dd>{{ document.bankName || tr('未记录') }}</dd></div>
        <div><dt>{{ tr("收款账号") }}</dt><dd>{{ document.bankAccount || tr('未记录') }}</dd></div>
      </dl>
      <p class="pi-field-help">{{ fixedLayoutName ? tr('可调整卖方与银行资料并保存新版本；本销售入口的单据版式保持固定。') : tr('如需调整卖方资料、默认银行信息或 PI 版式，可点击模板卡片上的“调整模板”，保存为新版本。') }}</p>
    </details>
  </section>
</template>
