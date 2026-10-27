<script setup lang="ts">
import PageTitle from './PageTitle.vue'
defineProps<{ title: string; description: string; theme: string; group: string; motto: string; workshop?: boolean }>()
</script>
<template>
  <section class="sm-hero" :class="`sm-hero--${theme}`">
    <div class="sm-hero-scene" aria-hidden="true"><img :src="`/images/smart-manufacturing/${workshop ? 'production-workshop' : theme}.webp`" alt="" /></div>
    <div class="sm-hero-copy"><PageTitle :title="title" :description="description" /><p class="sm-hero-brandline">SMART MANUFACTURING FOR E-BIKE</p></div>
    <div class="sm-hero-aside"><slot name="aside"><div v-if="workshop" class="pc-hero-motto"><strong>GREEN<br />SMART<br />EFFICIENT</strong><small>用科技铸就更好出行</small></div><div v-else class="sm-hero-motto"><span>SMART MANUFACTURING</span><strong>{{ motto }}</strong><small>BIKE · {{ group }}</small></div></slot></div>
  </section>
</template>
