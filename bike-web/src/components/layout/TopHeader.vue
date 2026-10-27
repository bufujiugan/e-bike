<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from '../AppIcon.vue'
import AccountMenu from './AccountMenu.vue'
import type { NavGroup, NavItem } from '../../config/page-presentation'
const props = defineProps<{ groups: NavGroup[]; username: string; role: string; lastLoaded: string; title: string; group: string }>()
const emit = defineEmits<{ navigate: [key: string]; settings: []; logout: [] }>()
function flatten(items: NavItem[]): NavItem[] { return items.flatMap(item => [...(item.path ? [item] : []), ...flatten(item.children ?? [])]) }
const searchGroups = computed(() => props.groups.map(group => ({ ...group, items: flatten(group.items) })))
const afterSalesEntry = computed(() => searchGroups.value.flatMap(group => group.items).find(item => item.key === 'after-sales'))
function refreshPage() { window.location.reload() }
</script>
<template>
  <header class="sm-top-header workspace-header">
    <div class="workspace-header-context">
      <nav class="workspace-breadcrumb" aria-label="当前位置"><span>工作空间</span><i>/</i><span>{{ group }}</span></nav>
      <strong class="workspace-page-title" :title="title">{{ title }}</strong>
    </div>
    <el-select model-value="" filterable placeholder="搜索功能页面…" aria-label="搜索功能页面" class="workspace-global-search" @change="emit('navigate', $event)"><template #prefix><AppIcon name="search" /></template><el-option-group v-for="group in searchGroups" :key="group.label" :label="group.label"><el-option v-for="item in group.items" :key="item.key" :label="item.label" :value="item.key" /></el-option-group></el-select>
    <div class="workspace-header-tools">
      <button type="button" class="workspace-tool-button" aria-label="刷新当前页面" :title="`刷新当前页面 · 最近加载 ${lastLoaded}`" @click="refreshPage"><AppIcon name="refresh" /></button>
      <el-popover trigger="click" placement="bottom-end" :width="280" popper-class="workspace-notifications">
        <template #reference><button type="button" class="workspace-tool-button" title="通知" aria-label="通知"><AppIcon name="bell" /></button></template>
        <div class="notification-content"><strong>通知</strong><p>业务通知随记录查看，当前未提供汇总通知列表。</p><el-button v-if="afterSalesEntry" link type="primary" @click="emit('navigate', afterSalesEntry.key)">前往售后案例</el-button></div>
      </el-popover>
      <span class="workspace-tools-divider" aria-hidden="true"></span>
      <AccountMenu :username="username" :role="role" @settings="emit('settings')" @logout="emit('logout')" />
    </div>
  </header>
</template>

<style scoped>
.sm-top-header.workspace-header { position: relative; inset: auto; display: flex; flex-direction: row; align-items: center; justify-content: flex-start; gap: clamp(.8rem, 1.6vw, 1.75rem); width: 100%; max-width: none; height: auto; min-height: 5rem; padding: .75rem clamp(1rem, 1.8vw, 2rem); background: #fff; border-bottom: 1px solid #e7edf5; }
.workspace-header-context { flex: 0 1 auto; min-width: 0; }
.workspace-breadcrumb { display: flex; align-items: center; gap: .5rem; color: #8193a8; font-size: .7rem; white-space: nowrap; }
.workspace-breadcrumb i { font-style: normal; color: #b4c1ce; }
.workspace-page-title { display: block; max-width: 16rem; margin-top: .35rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #17324f; font-size: .95rem; font-weight: 600; }
.workspace-global-search { flex: 0 1 24rem; width: clamp(10rem, 26vw, 24rem); min-width: 8rem; }
.workspace-global-search :deep(.el-select__wrapper) { min-height: 2.65rem; background: #f3f7fc; border-radius: .6rem; box-shadow: 0 0 0 1px #e9eff7 inset; }
.workspace-global-search .app-icon { width: 1.1rem; height: 1.1rem; color: #6d84a0; }
.workspace-header-tools { display: flex; align-items: center; gap: .6rem; margin-left: auto; flex-shrink: 0; }
.workspace-tool-button { display: grid; place-items: center; width: 2.3rem; height: 2.3rem; padding: 0; border: 0; border-radius: .5rem; background: transparent; color: #375472; }
.workspace-tool-button .app-icon { width: 1.25rem; height: 1.25rem; }
.workspace-tool-button:hover { background: #eff5fd; color: #1677ff; }
.workspace-tool-button:focus-visible { outline: 2px solid #1677ff; outline-offset: 2px; }
.workspace-tools-divider { height: 1.6rem; width: 1px; background: #e7edf5; margin: 0 .2rem; }
.notification-content { line-height: 1.7; color: #344c67; }
.notification-content p { color: #7a8b9e; font-size: .8rem; }
@media (max-width: 760px) {
  .sm-top-header.workspace-header { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: .6rem; min-height: 0; padding: .7rem; }
  .workspace-breadcrumb { font-size: .6rem; gap: .25rem; }
  .workspace-breadcrumb > span:first-child, .workspace-breadcrumb > i { display: none; }
  .workspace-page-title { font-size: .8rem; }
  .workspace-header-tools { grid-column: 2; grid-row: 1; gap: .1rem; }
  .workspace-tool-button { width: 1.9rem; height: 2rem; }
  .workspace-tools-divider { display: none; }
  .workspace-global-search { grid-row: 2; grid-column: 1 / -1; width: 100%; min-width: 0; }
  .workspace-global-search :deep(.el-select__wrapper) { min-height: 2.25rem; }
}
</style>
