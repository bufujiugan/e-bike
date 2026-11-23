<script setup lang="ts">
import { tr } from '../i18n/language'
import { computed, ref, watch } from 'vue'
import { peekSalesIntent } from '../sales-routing'
import type { PermissionContext } from '../permissions'
import { hasRole } from '../permissions'
import PiWorkspace from './PiWorkspace.vue'
import type { PiSalesScenario } from '../pi-sales-scenario'

const props = defineProps<{ context: (PermissionContext & { userType?: string; username?: string }) | null }>()
const emit = defineEmits<{ navigate: [view: string] }>()
// 历史 PI 保留原销售标识，欧洲页面的所有入口继续按实际出货仓查询。
const mode = ref<PiSalesScenario | 'HISTORY'>('CZECH_EXPORT')
const agent = computed(() => hasRole(props.context, 'WEB_AGENT') || props.context?.userType === 'AGENT')
const intent = computed(() => agent.value ? null : peekSalesIntent('EUROPE'))
watch(intent, value => { if (value) mode.value = value.europeTradeType === 'DOMESTIC' ? 'CZECH_DOMESTIC' : 'CZECH_EXPORT' }, { immediate: true })
</script>

<template>
  <section>
    <template v-if="!agent">
      <p>{{ tr("欧洲内销和外销均办理 PI，统一使用 France_1104_PI 模板，按实际交易填写税费、收款资料及交付安排。") }}</p>
      <el-radio-group v-model="mode" :aria-label='tr("欧洲销售类型")' class="sales-scenario-tabs">
        <el-radio-button value="CZECH_DOMESTIC">{{ tr("内销") }}</el-radio-button>
        <el-radio-button value="CZECH_EXPORT">{{ tr("外销") }}</el-radio-button>
        <el-radio-button value="HISTORY">{{ tr("全部／历史 PI") }}</el-radio-button>
      </el-radio-group>
    </template>
    <PiWorkspace :key="agent ? 'agent' : mode" :context="context" europe
      :sales-scenario="agent || mode === 'HISTORY' ? undefined : mode"
      :allow-create="mode !== 'HISTORY'" :intent="mode === 'HISTORY' ? null : intent" @navigate="emit('navigate', $event)" />
  </section>
</template>

<style scoped>.sales-scenario-tabs{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:20px}</style>
