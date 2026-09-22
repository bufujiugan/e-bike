<script setup lang="ts">
import type { ShippingDetails } from '../after-sales-api'
const form = defineModel<ShippingDetails>({ required: true })
defineProps<{ dispatch?: boolean }>()
</script>
<template>
  <div class="shipping-fields">
    <el-form-item label="发货仓库" :required="dispatch"><el-input v-model="form.warehouseName" maxlength="128" /></el-form-item>
    <el-form-item label="收货人" :required="dispatch"><el-input v-model="form.recipient" maxlength="100" /></el-form-item>
    <el-form-item label="收货电话" :required="dispatch"><el-input v-model="form.phone" maxlength="64" /></el-form-item>
    <el-form-item label="收货国家代码" :required="dispatch"><el-input v-model="form.countryCode" maxlength="2" placeholder="例如 GB、CN" /></el-form-item>
    <el-form-item label="省 / 州"><el-input v-model="form.region" maxlength="100" /></el-form-item>
    <el-form-item label="城市"><el-input v-model="form.city" maxlength="100" /></el-form-item>
    <el-form-item class="wide" label="详细收货地址" :required="dispatch"><el-input v-model="form.addressLine" maxlength="500" /></el-form-item>
    <el-form-item label="邮政编码"><el-input v-model="form.postalCode" maxlength="32" /></el-form-item>
    <el-form-item label="预计到达日期"><el-date-picker v-model="form.expectedArrivalDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
    <el-form-item v-if="dispatch" label="实际发货时间"><el-date-picker v-model="form.shippedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="留空使用当前时间" /></el-form-item>
  </div>
</template>
<style scoped>
.shipping-fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 16px}.wide{grid-column:1/-1}.shipping-fields :deep(.el-date-editor){width:100%}@media(max-width:600px){.shipping-fields{grid-template-columns:1fr}}
</style>
