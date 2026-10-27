<script setup lang="ts">
import { computed } from 'vue'
import type { Tone } from '../../config/page-presentation'
const props = defineProps<{ label: string; tone?: Tone }>()
const resolvedTone = computed(() => props.tone ?? (/异常|失败|缺口|报废|FAILED|ERROR/.test(props.label) ? 'red' : /撤销|取消|作废|关闭|未关联|未生成|CANCEL|CLOSED/.test(props.label) ? 'muted' : /已完成|已入库|已出库|已履约|已释放|正常|启用|PASSED|COMPLETED|SHIPPED/.test(props.label) ? 'green' : /待|到期|隔离|未占用|PENDING|DRAFT/.test(props.label) ? 'orange' : 'blue'))
</script>
<template><span class="sm-status" :class="`sm-tone-${resolvedTone}`"><i />{{ label }}</span></template>
