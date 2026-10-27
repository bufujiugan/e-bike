<script setup lang="ts">
import PageHero from '../common/PageHero.vue'
import AppIcon from '../AppIcon.vue'
defineProps<{ date: string; loading: boolean; error: string }>()
</script>
<template>
  <PageHero class="cc-hero" title="生产运营指挥中心" description="数据驱动 · 精益制造 · 全程可追溯" theme="command-center" group="工作台" motto="数据驱动制造 · 让每一辆好车可追溯">
    <template #aside><div class="cc-date-panel"><strong>{{ date.replace(/-/g, ' / ') }}</strong><span :class="{ 'is-error': error }"><AppIcon :name="error ? 'warning' : loading ? 'clock' : 'check'" />{{ error ? '运营数据加载失败' : loading ? '正在同步运营数据' : '运营数据已加载' }}</span></div></template>
  </PageHero>
</template>
