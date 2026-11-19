<script setup lang="ts">
import { computed, ref } from 'vue'
import type { PermissionContext } from '../permissions'
import { hasRole } from '../permissions'
import PiWorkspace from './PiWorkspace.vue'
import type { PiSalesScenario } from '../pi-sales-scenario'

const props = defineProps<{ context: (PermissionContext & { userType?: string; username?: string }) | null }>()
const emit = defineEmits<{ navigate: [view: string] }>()
// 历史 PI 延续原菜单归属，全部入口同时支持跨业务查询。
const mode = ref<PiSalesScenario | 'HISTORY'>('CZECH_EXPORT')
const agent = computed(() => hasRole(props.context, 'WEB_AGENT') || props.context?.userType === 'AGENT')
</script>

<template>
  <section>
    <template v-if="!agent">
      <p>捷克销售：内销和外销均办理 PI，按实际交易填写税费、收款资料及交付安排。</p>
      <el-radio-group v-model="mode" aria-label="捷克销售类型" class="sales-scenario-tabs">
        <el-radio-button value="CZECH_DOMESTIC">捷克内销 · PI</el-radio-button>
        <el-radio-button value="CZECH_EXPORT">捷克外销 · PI</el-radio-button>
        <el-radio-button value="HISTORY">全部／历史 PI</el-radio-button>
      </el-radio-group>
    </template>
    <PiWorkspace :key="agent ? 'agent' : mode" :context="context"
      :sales-scenario="agent || mode === 'HISTORY' ? undefined : mode"
      :allow-create="mode !== 'HISTORY'" @navigate="emit('navigate', $event)" />
  </section>
</template>

<style scoped>.sales-scenario-tabs{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:20px}</style>
