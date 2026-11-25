<script setup lang="ts">
import { tr } from '../i18n/language'
import { transportOptions, type TransportArrangement } from '../order-transport'

const arrangement = defineModel<TransportArrangement | null>('arrangement', { required: true })
const remark = defineModel<string>('remark', { required: true })
</script>

<template>
  <el-form-item :label='tr("运输安排")'>
    <el-select v-model="arrangement" :aria-label='tr("运输安排")' :placeholder='tr("请选择运输安排")'
      :value-on-clear="null" clearable>
      <el-option v-for="option in transportOptions" :key="option.value" :label="tr(option.label)" :value="option.value" />
    </el-select>
  </el-form-item>
  <el-form-item :label='tr("运输备注")'>
    <el-input v-model="remark" :aria-label='tr("运输备注")' type="textarea" :rows="2" maxlength="500" show-word-limit
      :placeholder='tr("可填写送货地址、物流公司、运单号或提货人")' />
  </el-form-item>
</template>
