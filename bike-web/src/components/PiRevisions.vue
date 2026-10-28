<script setup lang="ts">
import { createUuid } from '../utils/uuid'
import { ref, watch } from 'vue'
import { piApi, type PiView } from '../pi-api'

const props = defineProps<{ pi: PiView; canWrite: boolean }>()
const emit = defineEmits<{ select: [pi: PiView] }>()
const visible = ref(false), busy = ref(false), error = ref(''), reason = ref('')
const rows = ref<PiView[]>([])
let version = 0, requestId = '', fingerprint = ''
const states: Record<string, string> = { DRAFT: '草稿', CONFIRMED: '已确认', RELEASED: '已放行', SUPERSEDED: '历史版本', CANCELLED: '已作废' }
function message(cause: unknown) {
  return (cause as { response?: { data?: { message?: string } } })?.response?.data?.message || (cause as Error)?.message || '版本操作失败，请重试'
}
watch(() => props.pi.id, () => { version++; visible.value = false; busy.value = false; rows.value = []; reason.value = ''; requestId = ''; fingerprint = '' })
async function open() {
  const current = ++version, id = props.pi.id
  visible.value = true; busy.value = true; error.value = ''; rows.value = []
  try { const result = await piApi.revisions(id); if (current === version) rows.value = result }
  catch (cause) { if (current === version) error.value = message(cause) }
  finally { if (current === version) busy.value = false }
}
async function create() {
  if (busy.value || !props.canWrite) return
  if (!reason.value.trim() || reason.value.length > 500) { error.value = '请输入 1–500 字修订原因'; return }
  const id = props.pi.id, current = version
  const next = JSON.stringify([id, props.pi.revision, reason.value.trim()])
  if (fingerprint !== next) { requestId = createUuid(); fingerprint = next }
  busy.value = true; error.value = ''
  try {
    const result = await piApi.createRevision(id, { requestId, expectedRevision: props.pi.revision, reason: reason.value.trim() })
    if (current === version) { visible.value = false; emit('select', result) }
  } catch (cause) { if (current === version) error.value = message(cause) }
  finally { if (current === version) busy.value = false }
}
function select(row: PiView) { visible.value = false; emit('select', row) }
</script>

<template>
  <el-button @click="open">修订与版本历史</el-button>
  <el-dialog v-model="visible" title="PI 修订与版本历史" width="min(850px, 94vw)" append-to-body :close-on-click-modal="false">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table :data="rows" v-loading="busy">
      <el-table-column prop="revision" label="版本" width="70" />
      <el-table-column label="状态"><template #default="{ row }">{{ states[row.status] || row.status }}</template></el-table-column>
      <el-table-column prop="currencyCode" label="币种" width="70" />
      <el-table-column prop="totalAmount" label="单据金额" />
      <el-table-column prop="createdAt" label="创建时间" min-width="170" />
      <el-table-column label="操作" width="110"><template #default="{ row }"><el-button link type="primary" :disabled="busy" @click="select(row)">查看版本</el-button></template></el-table-column>
    </el-table>
    <template v-if="canWrite && ['CONFIRMED', 'RELEASED'].includes(pi.status)">
      <p>新修订创建为草稿。确认后替代当前版本，并须重新核对发货放行；旧文件、收款及已发车辆保留。</p>
      <el-alert v-if="rows.some(row => row.status === 'DRAFT')" title="已有修订草稿，请从上表打开继续编辑，或取消该草稿后重新创建。" type="info" :closable="false" />
      <template v-else>
        <el-input v-model="reason" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="填写本次修订原因" :disabled="busy" />
        <el-button type="primary" :loading="busy" @click="create" style="margin-top: 12px">创建修订草稿</el-button>
      </template>
    </template>
  </el-dialog>
</template>
