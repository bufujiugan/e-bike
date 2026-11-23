<script setup lang="ts">
import { tr } from '../i18n/language'
import { ref } from 'vue'
import axios from '../api'

type StockLocation = { frameSn: string; modelName: string; warehouseName: string; locationName: string }
const code = ref('')
const location = ref<StockLocation | null>(null)
const error = ref('')
const loading = ref(false)

async function lookup() {
  if (loading.value) return
  location.value = null
  const scannedCode = code.value.trim()
  if (!scannedCode) {
    error.value = '请扫描或输入整车聚合码、车架 SN'
    return
  }
  error.value = ''
  loading.value = true
  try {
    const response = await axios.get('/api/inventory/vehicle-location', {
      params: { code: scannedCode },
      headers: { Authorization: `Bearer ${localStorage.getItem('bike_access_token')}` },
    })
    location.value = response.data.data
  } catch (cause) {
    error.value = axios.isAxiosError(cause) ? cause.response?.data?.message ?? '查询失败，请重试' : '查询失败，请重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="form-section operation-section">
    <form class="pda-card operation-card" @submit.prevent="lookup">
      <van-field v-model="code" :label='tr("整车编码")' :placeholder='tr("扫描或输入聚合码、车架 SN")'
        maxlength="512" :disabled="loading" clearable autofocus @keydown.enter.prevent="lookup"
        @update:model-value="location = null; error = ''" />
      <van-button block type="primary" native-type="submit" :loading="loading">{{ tr("查询库存") }}</van-button>
    </form>
    <p v-if="error" role="alert">{{ tr(error) }}</p>
    <van-cell-group v-if="location" inset :title='tr("在库整车")'>
      <van-cell :title='tr("车架 SN")' :value="location.frameSn" />
      <van-cell :title='tr("车型")' :value="location.modelName" />
      <van-cell :title='tr("仓库")' :value="location.warehouseName" />
      <van-cell :title='tr("库位")' :value="location.locationName" />
    </van-cell-group>
  </section>
</template>
