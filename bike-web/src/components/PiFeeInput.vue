<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

const props = defineProps<{ modelValue: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const isEditing = ref(false), editingAmount = ref('')

function displayAmount(value: string): string {
  return value.trim().replace(/^(\d{1,16})\.0{1,2}$/, '$1')
}
const inputAmount = computed(() => isEditing.value ? editingAmount.value : displayAmount(props.modelValue))
onMounted(() => {
  // 兼容此前已保存为空的选填费用，恢复草稿后仍按零计算。
  if (props.modelValue.trim() === '') emit('update:modelValue', '0')
})

function beginEditing() {
  const amount = displayAmount(props.modelValue)
  // 聚焦只清空零值的显示，避免触发草稿变更或中断金额预览。
  editingAmount.value = /^0{1,16}(\.0{1,2})?$/.test(amount) ? '' : amount
  isEditing.value = true
}
function updateAmount(value: string) {
  editingAmount.value = value
  // 费用是选填项；留空按零计算，仍由原有校验拒绝非法金额。
  emit('update:modelValue', value.trim() === '' ? '0' : value)
}
</script>

<template>
  <el-input
    :model-value="inputAmount"
    inputmode="decimal"
    @focus="beginEditing"
    @blur="isEditing = false"
    @update:model-value="updateAmount"
  />
</template>
