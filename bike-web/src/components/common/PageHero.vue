<script setup lang="ts">
import { tr } from '../../i18n/language'
import PageTitle from './PageTitle.vue'
import MascotCompanion from './MascotCompanion.vue'
defineProps<{ title: string; description: string; theme: string; group: string; motto: string; workshop?: boolean }>()
</script>
<template>
  <section class="sm-hero" :class="`sm-hero--${theme}`">
    <div class="sm-hero-scene" aria-hidden="true"><MascotCompanion /></div>
    <div class="sm-hero-copy"><PageTitle :title="tr(title)" :description="tr(description)" /><p class="sm-hero-brandline">SMART MANUFACTURING FOR E-BIKE</p></div>
    <div class="sm-hero-aside"><slot name="aside"><div v-if="workshop" class="pc-hero-motto"><strong>GREEN<br />SMART<br />EFFICIENT</strong><small>{{ tr("用科技铸就更好出行") }}</small></div><div v-else class="sm-hero-motto"><span>SMART MANUFACTURING</span><strong>{{ motto }}</strong><small>BIKE · {{ group }}</small></div></slot></div>
  </section>
</template>
