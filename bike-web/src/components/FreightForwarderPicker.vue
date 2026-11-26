<script setup lang="ts">
import { ref } from 'vue'
import { freightForwarderApi, type FreightForwarder } from '../freight-forwarder-api'
import { tr } from '../i18n/language'

defineProps<{ disabled: boolean }>()
const emit = defineEmits<{ select: [forwarder: FreightForwarder] }>()
const selectedId = ref<number>()
const forwarders = ref<FreightForwarder[]>([])
const loading = ref(false)
const loadFailed = ref(false)

async function loadForwarders(open: boolean) {
  if (!open || loading.value) return
  loading.value = true
  loadFailed.value = false
  try {
    forwarders.value = await freightForwarderApi.list()
  } catch {
    forwarders.value = []
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

function selectForwarder(id: number | undefined) {
  const forwarder = forwarders.value.find(entry => entry.id === id)
  if (forwarder) emit('select', forwarder)
}
</script>

<template>
  <el-form-item :label="tr('选择货代信息')">
    <el-select v-model="selectedId" filterable clearable class="forwarder-select" :disabled="disabled"
      :loading="loading" :aria-label="tr('选择货代信息')" :placeholder="tr('选择已保存的货代')"
      :no-data-text="tr(loadFailed ? '货代信息读取失败' : '暂无货代信息')"
      @visible-change="loadForwarders" @change="selectForwarder">
      <el-option v-for="forwarder in forwarders" :key="forwarder.id" :value="forwarder.id"
        :label="[forwarder.name, forwarder.contactName, forwarder.address.replace(/\s+/g, ' ')].join(' · ')" />
    </el-select>
    <a class="forwarder-directory-link" href="/freight-forwarders" target="_blank" rel="noopener">
      {{ tr('货代信息管理（在新标签页打开）') }}
    </a>
  </el-form-item>
</template>

<style scoped>
.forwarder-select { width: 100%; }
.forwarder-directory-link { color: var(--el-color-primary); text-decoration: underline; }
</style>
