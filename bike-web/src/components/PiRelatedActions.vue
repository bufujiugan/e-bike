<script lang="ts">
export type PiRelatedAction = { key: string; label: string; steps: string[]; href?: string; unavailable?: string }
</script>
<script setup lang="ts">
import { tr } from '../i18n/language'
import { computed } from 'vue'
const props = defineProps<{ actions: PiRelatedAction[]; step?: string; busy?: boolean }>()
const emit = defineEmits<{ select: [key: string] }>()
const visibleActions = computed(() => props.step ? props.actions.filter(action => action.steps.includes(props.step!)) : props.actions)
</script>
<template>
  <div v-if="visibleActions.length" class="pi-related-links" :aria-label='tr("相关维护入口")'>
    <template v-for="action in visibleActions" :key="action.key">
      <span v-if="action.unavailable">{{ action.unavailable }}</span>
      <a v-else-if="action.href" :href="action.href" target="_blank" rel="noopener noreferrer">{{ tr(action.label) }} ↗</a>
      <el-button v-else link type="primary" :disabled="busy" @click="emit('select', action.key)">{{ tr(action.label) }}</el-button>
    </template>
  </div>
</template>
<style scoped>
.pi-related-links{display:flex;flex-wrap:wrap;align-items:center;gap:8px 16px;margin:8px 0;font-size:13px}.pi-related-links a{color:#1677c8;text-decoration:none}.pi-related-links .el-button{margin:0}.pi-related-links span{color:#65758a}
</style>
