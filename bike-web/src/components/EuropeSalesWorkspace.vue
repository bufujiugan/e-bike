<script setup lang="ts">
import { ref } from 'vue'
import type { PermissionContext } from '../permissions'
import CzechPiWorkspace from './CzechPiWorkspace.vue'
import FactorySalesWorkspace from './FactorySalesWorkspace.vue'
import EuropeSalesGuide from './EuropeSalesGuide.vue'
defineProps<{ context: (PermissionContext & { userType?: string; username?: string }) | null }>()
const emit=defineEmits<{navigate:[view:string]}>()
const mode=ref('pi'),guideVisible=ref(false)
</script>
<template>
  <section class="europe-sales">
    <div class="europe-title"><div><h2>欧洲销售与交付</h2><p>先选择交易方式：正常 PI 销售，或工厂直接签约收款。</p></div><el-button @click="guideVisible=true">查看外销操作说明</el-button></div>
    <div class="europe-modes" role="group" aria-label="欧洲销售业务类型"><button type="button" :class="{active:mode==='pi'}" @click="mode='pi'"><strong>正常 PI 销售</strong><span>先收款／货到付款 · 跟进付 Totem</span></button><button type="button" :class="{active:mode==='domestic'}" @click="mode='domestic'"><strong>捷克代理当地内销</strong><span>工厂直签 · 含 VAT · 先付款后提货</span></button><button type="button" :class="{active:mode==='export'}" @click="mode='export'"><strong>工厂直签外销</strong><span>不做 PI · 按合同不含 VAT · 先付款后提货</span></button></div>
    <KeepAlive><CzechPiWorkspace v-if="mode==='pi'" :context="context" @navigate="emit('navigate',$event)" /><FactorySalesWorkspace v-else :key="mode" :context="context" :business-type="mode==='domestic'?'FACTORY_DOMESTIC':'FACTORY_EXPORT'" /></KeepAlive>
    <el-dialog v-model="guideVisible" title="外销／欧洲销售操作说明" width="min(1050px,96vw)" append-to-body><EuropeSalesGuide /></el-dialog>
  </section>
</template>
<style scoped>.europe-title{display:flex;justify-content:space-between;align-items:center;gap:18px;flex-wrap:wrap}.europe-title h2{margin:0}.europe-title p{color:#65758a}.europe-modes{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;margin:18px 0 24px}.europe-modes button{display:grid;gap:10px;text-align:left;padding:20px;border:1px solid #d5e0ea;border-radius:10px;background:#fff;cursor:pointer;color:#264861}.europe-modes strong{font-size:16px}.europe-modes span{line-height:1.7;color:#647b8e}.europe-modes .active{background:#edf5ff;border-color:#347bbb;box-shadow:inset 0 3px #347bbb}@media(max-width:700px){.europe-modes{grid-template-columns:1fr;gap:9px}.europe-modes button{padding:14px}}</style>
