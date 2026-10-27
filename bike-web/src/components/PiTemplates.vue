<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { piApi, type PiTemplate, type PiTemplatePublish } from '../pi-api'
const props = defineProps<{ tradeType?: string; canManage: boolean; selectable?: boolean }>()
const emit = defineEmits<{ select: [value: PiTemplate] }>()
const rows = ref<PiTemplate[]>([]), total = ref(0), page = ref(1), busy = ref(false), error = ref(''), editing = ref(false)
const fields = [['templateCode', '模板编码'], ['templateVersion', '版本号'], ['sellerName', '卖方名称'], ['sellerAddress', '卖方地址'], ['sellerCountry', '卖方国家/地区'], ['sellerContactName', '卖方联系人'], ['sellerPhone', '卖方电话'], ['sellerEmail', '卖方Email'], ['bankName', '收款银行'], ['bankAccount', '收款账号'], ['beneficiary', '收款单位'], ['reason', '发布原因']] as const
const draft = reactive<PiTemplatePublish>({ templateCode: '', templateVersion: '', tradeType: 'DOMESTIC', layoutCode: 'DOMESTIC_V3', sellerName: '', sellerAddress: '', sellerContact: '', sellerCountry: '', sellerContactName: '', sellerPhone: '', sellerEmail: '', bankName: '', bankAccount: '', beneficiary: '', reason: '' })
let version = 0
const formatDateTime = (value?: string | null) => value ? value.replace('T', ' ').slice(0, 19) : '—'
async function load() {
  const current = ++version; busy.value = true; error.value = ''
  try { const data = await piApi.templates(props.tradeType, page.value); if (current === version) { rows.value = data.records; total.value = data.total } }
  catch (e) { if (current === version) error.value = api.isAxiosError(e) ? e.response?.data?.message || '模板加载失败' : '模板加载失败' }
  finally { if (current === version) busy.value = false }
}
watch(() => props.tradeType, () => { page.value = 1; void load() }, { immediate: true })
async function publish() {
  if (!props.canManage || busy.value) return
  if (fields.some(([key]) => !draft[key].trim())) { error.value = '请填写全部模板和银行资料'; return }
  try {
    await ElMessageBox.confirm('发布后此版本不可修改。请核对卖方及银行资料；后续变更必须发布新版本。', '发布模板', { type: 'warning' })
    busy.value = true; error.value = ''
    const result = await piApi.publishTemplate({ ...draft, layoutCode: `${draft.tradeType}_V3` })
    editing.value = false; page.value = 1; await load(); ElMessage.success('模板版本已发布')
    if (props.selectable && result.tradeType === props.tradeType) emit('select', result)
  } catch (e) { if (e !== 'cancel' && e !== 'close') error.value = api.isAxiosError(e) ? e.response?.data?.message || '发布失败' : '发布失败' }
  finally { busy.value = false }
}
</script>
<template>
  <section v-loading="busy">
    <p>选择已发布版本，卖方和银行资料会固定带入单据。历史 V1/V2 版本保留查询，新单据须使用资料完整的 V3 版本。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-button @click="load">刷新模板</el-button><el-button v-if="canManage" @click="editing = !editing">发布新版本</el-button>
    <el-table :data="rows" empty-text="暂无已发布模板，请联系模板管理员">
      <el-table-column prop="templateCode" label="模板" /><el-table-column prop="templateVersion" label="版本" width="80" />
      <el-table-column label="类型" width="80"><template #default="s">{{ s.row.tradeType === 'EXPORT' ? '外销' : '内销' }}</template></el-table-column>
      <el-table-column label="版式" width="170"><template #default="s">{{ s.row.layoutCode.endsWith('_V3') ? 'V3 完整卖方资料' : '历史版式（仅供查询）' }}</template></el-table-column>
      <el-table-column prop="sellerName" label="卖方" /><el-table-column prop="bankName" label="银行" /><el-table-column prop="bankAccount" label="账号" />
      <el-table-column label="创建时间" min-width="175"><template #default="s">{{ formatDateTime(s.row.publishedAt) }}</template></el-table-column>
      <el-table-column label="修改时间" min-width="175"><template #default="s">{{ formatDateTime(s.row.publishedAt) }}</template></el-table-column>
      <el-table-column type="expand"><template #default="s"><el-descriptions :column="2" border><el-descriptions-item v-for="key in ['sellerAddress', 'sellerCountry', 'sellerContactName', 'sellerPhone', 'sellerEmail']" :key="key" :label="fields.find(f => f[0] === key)?.[1]">{{ s.row[key] || '历史未记录' }}</el-descriptions-item></el-descriptions></template></el-table-column>
      <el-table-column v-if="selectable" width="80"><template #default="s"><el-button link type="primary" :disabled="!s.row.layoutCode.endsWith('_V3')" @click="emit('select', s.row)">选用</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next, total" @current-change="load" />
    <el-form v-if="editing && canManage" label-position="top" @submit.prevent="publish">
      <el-form-item label="固定版式"><el-select v-model="draft.tradeType"><el-option value="DOMESTIC" label="内销" /><el-option value="EXPORT" label="外销" /></el-select></el-form-item>
      <el-form-item v-for="[key, label] in fields" :key="key" :label="label" required><el-input v-model="draft[key]" :type="key === 'sellerEmail' ? 'email' : 'text'" :maxlength="key === 'sellerAddress' ? 1000 : key === 'reason' ? 500 : key === 'sellerEmail' ? 254 : ['sellerCountry', 'sellerContactName'].includes(key) ? 100 : key === 'sellerPhone' || key === 'templateCode' ? 64 : key === 'templateVersion' ? 32 : 200" /></el-form-item>
      <el-form-item label="补充联系方式（可选）"><el-input v-model="draft.sellerContact" maxlength="300" /></el-form-item>
      <el-button type="primary" native-type="submit" :loading="busy">核对并发布</el-button>
    </el-form>
  </section>
</template>
