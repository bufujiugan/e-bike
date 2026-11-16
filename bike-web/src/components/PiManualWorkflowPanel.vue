<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { PiWorkflow, PiWorkflowComplete } from '../pi-api'
const props = defineProps<{ workflow: PiWorkflow | null; busy: boolean; error: string; canWrite: boolean; canRelease: boolean; piStatus: string }>()
const emit = defineEmits<{ complete: [code: string, body: PiWorkflowComplete]; confirmPi: []; release: [] }>()
const current = computed(() => props.workflow?.steps.find(step => step.status !== 'COMPLETED'))
const selected = ref(''), note = ref('')
watch(() => props.workflow, () => { selected.value = current.value?.code || ''; note.value = current.value?.note || '' }, { immediate: true })
function select(code: string) { selected.value = code; note.value = props.workflow?.steps.find(step => step.code === code)?.note || '' }
</script>
<template>
  <section class="manual-flow" v-loading="busy">
    <strong>欧洲销售 · 简化办理</strong><p>正常填写，确认后进入下一步。合同、附件和到账资料允许后补；流程确认不代表实际到账或已经出库。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="manual-steps"><button v-for="step in workflow?.steps.filter(s=>s.code!=='PI_CREATED')" :key="step.code" type="button" :class="{active:selected===step.code,done:step.status==='COMPLETED'}" @click="select(step.code)">{{ step.status==='COMPLETED'?'✓ ':'' }}{{ step.name }}</button></div>
    <template v-if="piStatus==='DRAFT'"><el-button v-if="canWrite" type="primary" @click="emit('confirmPi')">确认并发布 PI</el-button></template>
    <template v-else-if="!['CANCELLED','SUPERSEDED'].includes(piStatus)"><el-input v-if="selected" v-model="note" type="textarea" maxlength="2000" placeholder="填写本环节办理情况，可后补" :disabled="!canWrite" /><div class="manual-actions"><el-button v-if="canWrite && selected" type="primary" :disabled="busy" @click="emit('complete',selected,{note:note||null})">{{ workflow?.steps.find(s=>s.code===selected)?.status==='COMPLETED'?'保存补充说明':'确认并下一步' }}</el-button><el-button v-if="canRelease && piStatus==='CONFIRMED'" :disabled="busy" @click="emit('release')">确认放行，准备系统出库</el-button><span v-if="piStatus==='RELEASED'">已放行，实际出库由仓库办理。</span></div></template>
  </section>
</template>
<style scoped>.manual-flow{padding:16px;background:#f4f8fc;border-radius:8px;margin:14px 0}.manual-flow p{line-height:1.8;color:#596c7d}.manual-steps{display:flex;flex-wrap:wrap;gap:8px;margin:12px 0}.manual-steps button{border:1px solid #c8d8e7;background:white;padding:10px 12px;border-radius:6px;color:#294a67;cursor:pointer}.manual-steps button.active{border-color:#246bb0;background:#e6f1fc}.manual-steps button.done{color:#21724a}.manual-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-top:12px}</style>
