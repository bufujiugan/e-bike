<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
const props = defineProps<{ value?: string | null }>()
const contractNo = computed(() => props.value?.trim() || '')
async function copyContractNumber() {
  if (!contractNo.value) return
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(contractNo.value)
    } else {
      // 内网 HTTP 页面不提供 Clipboard API，保留兼容复制入口。
      const previous = document.activeElement as HTMLElement | null
      const input = document.createElement('textarea')
      input.value = contractNo.value
      input.setAttribute('readonly', '')
      input.style.cssText = 'position:fixed;left:-9999px;top:0'
      const container = previous?.closest('[role="dialog"]') || document.body
      container.appendChild(input)
      try {
        input.select()
        if (!document.execCommand('copy')) throw new Error('复制未成功')
      } finally { input.remove(); previous?.focus({ preventScroll: true }) }
    }
    ElMessage.success('合同号已复制')
  } catch { ElMessage.error('复制失败，请选中合同号手动复制') }
}
</script>
<template>
  <span class="purchase-contract-number"><span>{{ contractNo || '未登记' }}</span><el-button v-if="contractNo && contractNo !== '-'" link type="primary" @click.stop="copyContractNumber">复制合同号</el-button></span>
</template>
<style scoped>
.purchase-contract-number{display:inline-flex;align-items:center;gap:8px;flex-wrap:wrap;max-width:100%;overflow-wrap:anywhere}.purchase-contract-number>span{min-width:0;white-space:normal}.purchase-contract-number .el-button{flex-shrink:0}
</style>
