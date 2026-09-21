<script setup lang="ts">
import type { CompanyContact } from '../after-sales-company-api'
defineProps<{ model: CompanyContact; disabled?: boolean }>()
</script>
<template>
  <div class="contact-grid">
    <el-form-item label="联系人"><el-input v-model="model.contactName" :disabled="disabled" maxlength="100" /></el-form-item>
    <el-form-item label="联系电话"><el-input v-model="model.contactPhone" :disabled="disabled" maxlength="64" /></el-form-item>
    <el-form-item label="Email"><el-input v-model="model.contactEmail" :disabled="disabled" type="email" maxlength="254" /></el-form-item>
    <el-form-item label="国家代码"><el-input v-model="model.countryCode" :disabled="disabled" maxlength="2" placeholder="如 CN、GB、DE" /></el-form-item>
    <el-form-item label="省 / 州"><el-input v-model="model.region" :disabled="disabled" maxlength="100" /></el-form-item>
    <el-form-item label="城市"><el-input v-model="model.city" :disabled="disabled" maxlength="100" /></el-form-item>
    <el-form-item label="详细地址"><el-input v-model="model.addressLine" :disabled="disabled" maxlength="500" /></el-form-item>
    <el-form-item label="邮政编码"><el-input v-model="model.postalCode" :disabled="disabled" maxlength="32" /></el-form-item>
  </div>
</template>
<style scoped>.contact-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 18px}@media(max-width:680px){.contact-grid{grid-template-columns:1fr}}</style>
