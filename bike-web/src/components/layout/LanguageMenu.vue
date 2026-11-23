<script setup lang="ts">
import { nextTick, ref } from 'vue'
import type { DropdownInstance } from 'element-plus'
import AppIcon from '../AppIcon.vue'
import { language, tr, type Language } from '../../i18n/language'

const dropdown = ref<DropdownInstance>()
const trigger = ref<HTMLButtonElement>()
const isOpen = ref(false)
const options: { value: Language; code: string; label: string }[] = [
  { value: 'en', code: 'EN', label: 'English' },
  { value: 'zh-CN', code: '中', label: '简体中文' },
]

async function closeMenu() {
  dropdown.value?.handleClose()
  await nextTick()
  trigger.value?.focus()
}

function selectLanguage(value: Language) {
  language.value = value
  void closeMenu()
}
</script>

<template>
  <el-dropdown ref="dropdown" trigger="click" placement="bottom-end" :show-arrow="false"
    popper-class="bike-language-popover" @visible-change="isOpen = $event" @command="selectLanguage">
    <button ref="trigger" type="button" class="language-trigger" :class="{ 'is-open': isOpen }"
      :aria-label="tr('切换语言')" aria-haspopup="menu" :aria-expanded="isOpen" :title="tr('切换语言')"
      @keydown.esc.stop.prevent="closeMenu">
      <span>{{ language === 'en' ? 'EN' : '中文' }}</span>
      <AppIcon name="chevron" class="language-chevron" aria-hidden="true" />
    </button>
    <template #dropdown>
      <el-dropdown-menu class="language-options" :aria-label="tr('切换语言')" @keydown.esc.capture.stop.prevent="closeMenu">
        <el-dropdown-item v-for="option in options" :key="option.value" :command="option.value"
          :class="{ 'is-current': language === option.value }"
          :aria-current="language === option.value ? 'true' : undefined" :lang="option.value">
          <span class="language-code" aria-hidden="true">{{ option.code }}</span>
          <span>{{ option.label }}</span>
          <span class="language-check" aria-hidden="true">{{ language === option.value ? '✓' : '' }}</span>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style scoped>
  .language-trigger {
    display: inline-flex; align-items: center; gap: .28rem; min-height: 44px;
    padding: 0 .48rem; border: 0; border-radius: .55rem; background: transparent;
    color: var(--bike-text, #4b352d); font: inherit; font-size: .77rem; font-weight: 650; white-space: nowrap; cursor: pointer;
  }
  .language-trigger:hover, .language-trigger.is-open { background: var(--bike-surface-soft, #fbe9db); }
  .language-trigger:focus-visible { outline: 2px solid currentColor; outline-offset: 1px; }
  .language-chevron { width: .7rem; height: .7rem; transform: rotate(90deg); }
  .is-open .language-chevron { transform: rotate(-90deg); }
  .language-options { min-width: 9.4rem; padding: .26rem; }
  .language-options :deep(.el-dropdown-menu__item) {
    display: grid; grid-template-columns: 1.55rem 1fr .8rem; gap: .38rem;
    min-height: 44px; padding: 0 .72rem; border-radius: .4rem; font-size: .83rem; white-space: nowrap;
  }
  .language-options :deep(.is-current) { color: #87472f; background: #fbe9db; font-weight: 650; }
  .language-code { font-size: .67rem; font-weight: 700; }
  .language-check { text-align: center; }
  :global(.bike-language-popover.el-popper) { border-radius: .66rem; }
  @media (max-width: 760px) {
    .language-trigger { padding-inline: .28rem; gap: .12rem; }
  }
</style>
