<script setup lang="ts">
import { language, tr } from '../i18n/language'
</script>

<template>
  <label class="language-select">
    <span aria-hidden="true">{{ language === 'en' ? 'EN' : '中文' }} <van-icon name="arrow-down" /></span>
    <select v-model="language" :aria-label="tr('切换语言')">
      <option value="en" lang="en">English</option>
      <option value="zh-CN" lang="zh-CN">简体中文</option>
    </select>
  </label>
</template>

<style scoped>
  .language-select {
    position: relative; display: inline-grid; place-items: center; min-width: 3.45rem;
    min-height: 44px; padding-inline: .4rem; color: #173e6b; background: #fff;
    border: 1px solid #d9e8f7; border-radius: .5rem; white-space: nowrap; font-size: .78rem; font-weight: 700;
  }
  .language-select:focus-within { outline: 2px solid #1767ac; outline-offset: 1px; }
  select { position: absolute; inset: 0; width: 100%; opacity: 0; cursor: pointer; font-size: 16px; }
</style>
