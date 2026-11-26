<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { tr } from '../i18n/language'
import { customerStatusError, type Page, type PartPrice } from '../customer-status-api'

const keyword = ref('')
const search = ref('')
const page = ref(1)
const total = ref(0)
const prices = ref<PartPrice[]>([])
const loading = ref(false)
const error = ref('')
const editing = ref<PartPrice | null>(null)
const dialogVisible = ref(false)
const saving = ref(false)
const saveError = ref('')
const priceText = ref('')
const currencyText = ref('')
let readVersion = 0

async function load() {
  const version = ++readVersion
  loading.value = true
  error.value = ''
  try {
    const { data } = await api.get<{ data: Page<PartPrice> }>('/api/part-pricing', { params: { keyword: search.value, page: page.value } })
    if (version !== readVersion) return
    prices.value = data.data.records
    total.value = data.data.total
  } catch (cause) {
    if (version !== readVersion) return
    error.value = customerStatusError(cause, '零件价目加载失败')
  } finally {
    if (version === readVersion) loading.value = false
  }
}

function find() {
  search.value = keyword.value.trim()
  page.value = 1
  void load()
}

function edit(part: PartPrice) {
  editing.value = { ...part }
  priceText.value = part.unitPrice === null ? '' : String(part.unitPrice)
  currencyText.value = part.currency ?? ''
  saveError.value = ''
  dialogVisible.value = true
}

async function save() {
  if (!editing.value || saving.value) return
  const unitPrice = priceText.value.trim()
  const currency = currencyText.value.trim().toUpperCase()
  if ((unitPrice && !/^\d{1,16}(\.\d{1,2})?$/.test(unitPrice)) || (currency && !/^[A-Z]{3}$/.test(currency))) {
    saveError.value = '请填写最多两位小数的非负单价及三个字母的币种'
    return
  }
  if (Boolean(unitPrice) !== Boolean(currency) || (editing.value.sellable && !unitPrice)) {
    saveError.value = '单价和币种须同时填写，可售零件必须填写价格'
    return
  }
  saving.value = true
  saveError.value = ''
  try {
    await api.put('/api/part-pricing/' + editing.value.id, {
      sellable: editing.value.sellable,
      unitPrice: unitPrice || null,
      currency: currency || null,
    })
    dialogVisible.value = false
    ElMessage.success(tr('零件价目已保存'))
    await load()
  } catch (cause) {
    saveError.value = customerStatusError(cause, '零件价目保存失败，请重试')
  } finally {
    saving.value = false
  }
}

onMounted(load)
onBeforeUnmount(() => { readVersion++ })
</script>

<template>
  <section class="part-pricing">
    <form class="pricing-search" @submit.prevent="find">
      <el-input v-model="keyword" :maxlength="128" :aria-label="tr('零件编号或名称')" :placeholder="tr('零件编号或名称')" clearable @clear="find" />
      <el-button type="primary" native-type="submit">{{ tr('搜索') }}</el-button>
    </form>
    <el-alert v-if="error" :title="tr(error)" type="error" :closable="false" show-icon />
    <el-table :data="prices" v-loading="loading" :empty-text="tr('暂无零件')">
      <el-table-column prop="partNo" :label="tr('零件编号')" min-width="150" />
      <el-table-column prop="partName" :label="tr('零件名称')" min-width="100">
        <template #default="{ row }">{{ tr(row.partName) }}</template>
      </el-table-column>
      <el-table-column :label="tr('可售状态')" min-width="95">
        <template #default="{ row }"><el-tag :type="row.sellable ? 'success' : 'info'">{{ tr(row.sellable ? '可售' : '不可售') }}</el-tag></template>
      </el-table-column>
      <el-table-column :label="tr('单价')" min-width="95">
        <template #default="{ row }">{{ row.unitPrice ?? '—' }}</template>
      </el-table-column>
      <el-table-column prop="currency" :label="tr('币种')" min-width="75" />
      <el-table-column width="92">
        <template #default="{ row }"><el-button link type="primary" @click="edit(row)">{{ tr('编辑') }}</el-button></template>
      </el-table-column>
    </el-table>
    <el-pagination v-if="total > 20" v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next" @current-change="load" />
    <el-dialog v-model="dialogVisible" :title="tr('维护零件价目')" width="min(440px, 94vw)"
      :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
      <el-form v-if="editing" label-position="top" @submit.prevent="save">
        <el-form-item :label="tr('零件编号')">{{ editing.partNo }} · {{ tr(editing.partName) }}</el-form-item>
        <el-form-item :label="tr('可售状态')"><el-switch v-model="editing.sellable" :active-text="tr('可售')" :inactive-text="tr('不可售')" :aria-label="tr('可售状态')" :disabled="saving" /></el-form-item>
        <el-form-item :label="tr('单价')" :required="editing.sellable"><el-input v-model="priceText" inputmode="decimal" maxlength="19" :disabled="saving" /></el-form-item>
        <el-form-item :label="tr('币种')" :required="editing.sellable"><el-input v-model="currencyText" maxlength="3" placeholder="CNY / EUR / USD" :disabled="saving" /></el-form-item>
        <el-alert v-if="saveError" :title="tr(saveError)" type="error" :closable="false" show-icon />
        <div class="pricing-actions">
          <el-button :disabled="saving" @click="dialogVisible = false">{{ tr('取消') }}</el-button>
          <el-button native-type="submit" type="primary" :loading="saving">{{ tr('保存') }}</el-button>
        </div>
      </el-form>
    </el-dialog>
  </section>
</template>

<style scoped>
.part-pricing { display: grid; gap: 16px; min-width: 0; }
.pricing-search { display: flex; gap: 8px; max-width: 420px; }
.pricing-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 20px; }
</style>
