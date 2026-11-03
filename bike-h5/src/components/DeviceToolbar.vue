<script setup lang="ts">
import { isPda, openDeviceSettings, startDeviceScan } from '../device'
</script>
<template>
  <div v-if="isPda" class="device-toolbar">
    <span>608 手持终端</span>
    <van-button size="small" icon="scan" @click="startDeviceScan">扫描</van-button>
    <van-button size="small" icon="setting-o" @click="openDeviceSettings">设备设置</van-button>
  </div>
</template>
<style scoped>
.device-toolbar{display:flex;align-items:center;justify-content:flex-end;padding:8px 12px;background:#edf3f8;color:#23425b;font-size:12px}
.device-toolbar span{margin-right:auto}.device-toolbar button{margin-left:8px}
</style>
