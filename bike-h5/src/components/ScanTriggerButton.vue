<script setup lang="ts">
import { triggerScan } from '../device'

const props = defineProps<{ field?: { focus: () => void } | null; disabled?: boolean; label?: string }>()
</script>

<template>
  <button class="scan-trigger" type="button" :disabled="props.disabled" :aria-label="props.label ?? '启动扫描头'"
    @click.stop="triggerScan(props.field)">
    <van-icon name="scan" />
  </button>
</template>

<style scoped>
.scan-trigger{display:grid;flex:0 0 40px;width:40px;height:40px;padding:0;place-items:center;color:#0878f9;font-size:24px;border:0;border-left:1px solid #d7e6f6;background:#f6fbff}
.scan-trigger:disabled{color:#aebcca;background:#f4f7fa}
</style>
