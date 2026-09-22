<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { piApi } from '../pi-api'
const props = defineProps<{ piId: number; itemId: number }>()
const source = ref(''), error = ref(''), busy = ref(false)
let active = true
async function load() {
  if (source.value || busy.value) return
  busy.value = true; error.value = ''
  try { const file = await piApi.itemImage(props.piId, props.itemId); if (active) source.value = URL.createObjectURL(file) }
  catch { if (active) error.value = '图片读取或校验失败，请检查存储' }
  finally { busy.value = false }
}
onBeforeUnmount(() => { active = false; if (source.value) URL.revokeObjectURL(source.value) })
</script>
<template><el-popover width="340" trigger="click" @show="load"><template #reference><el-button link type="primary">查看图片</el-button></template><div v-loading="busy"><img v-if="source" :src="source" alt="此PI商品图片快照" style="display:block;max-width:310px;max-height:280px" /><p v-if="error">{{ error }}</p></div></el-popover></template>
