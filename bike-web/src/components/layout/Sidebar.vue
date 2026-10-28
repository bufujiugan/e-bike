<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import AppIcon from '../AppIcon.vue'
import type { NavGroup, NavItem } from '../../config/page-presentation'

const props = defineProps<{ groups: NavGroup[]; active: string; username: string; role: string; collapsed: boolean }>()
const emit = defineEmits<{ 'update:collapsed': [collapsed: boolean]; navigate: [key: string]; settings: []; logout: [] }>()
const collapsedGroups = ref(new Set(['生产作业', '仓储管理', '销售与交付', '售后与追溯', '资料与设置', '代理商工作台']))
const expandedSubmenus = ref(new Set<string>())
const navigation = ref<HTMLElement | null>(null)
const groupPresentation: Record<string, { id: string; icon: string }> = {
  工作台: { id: 'workbench', icon: 'home' },
  销售与交付: { id: 'sales', icon: 'orders' },
  生产作业: { id: 'manufacturing', icon: 'production' },
  仓储管理: { id: 'warehouse', icon: 'inventory' },
  售后与追溯: { id: 'service', icon: 'service' },
  资料与设置: { id: 'settings', icon: 'layers' },
  代理商工作台: { id: 'agent', icon: 'network' },
}

// 只归并已通过原权限过滤的菜单，不增删或重新授权业务入口。
const displayGroups = computed(() => ['工作台', '生产作业', '仓储管理', '销售与交付', '售后与追溯', '资料与设置', '代理商工作台'].flatMap(label => {
  const group = props.groups.find(entry => entry.label === label)
  return group?.items.length ? [{ ...group, ...groupPresentation[label] }] : []
}))

function toggleGroup(label: string) {
  const next = new Set(collapsedGroups.value)
  next.has(label) ? next.delete(label) : next.add(label)
  collapsedGroups.value = next
}
function hasActiveChild(item: NavItem) { return item.children?.some(child => child.key === props.active) ?? false }
function toggleSubmenu(key: string) {
  const next = new Set(expandedSubmenus.value)
  next.has(key) ? next.delete(key) : next.add(key)
  expandedSubmenus.value = next
}

// 权限菜单可能晚于路由返回，两者就绪后都需要同步当前路径。
watch([() => props.active, displayGroups], async ([key]) => {
  const group = displayGroups.value.find(entry => entry.items.some(item => item.key === key || item.children?.some(child => child.key === key)))
  if (group && collapsedGroups.value.has(group.label)) {
    const next = new Set(collapsedGroups.value)
    next.delete(group.label)
    collapsedGroups.value = next
  }
  const parent = displayGroups.value.flatMap(entry => entry.items).find(item => item.children?.length && (item.key === key || hasActiveChild(item)))
  if (parent && !expandedSubmenus.value.has(parent.key)) {
    const next = new Set(expandedSubmenus.value)
    next.add(parent.key)
    expandedSubmenus.value = next
  }
  await nextTick()
  const nav = navigation.value
  const selected = nav?.querySelector<HTMLElement>('[aria-current="page"]')
  if (!nav || !selected) return
  // 只滚动菜单容器，避免切换路由时连带移动右侧业务页面。
  const viewport = nav.getBoundingClientRect()
  const itemBounds = selected.getBoundingClientRect()
  if (itemBounds.top < viewport.top) nav.scrollTop += itemBounds.top - viewport.top - 4
  else if (itemBounds.bottom > viewport.bottom) nav.scrollTop += itemBounds.bottom - viewport.bottom + 4
}, { immediate: true })
</script>

<template>
  <aside class="sm-sidebar" :class="{ 'is-sidebar-collapsed': collapsed }">
    <div class="sm-sidebar-top">
      <div class="workspace-brand" title="BIKE 工作空间 · 智能制造管理平台">
        <span class="workspace-brand-mark" aria-hidden="true">B</span>
        <span class="workspace-brand-copy"><strong>BIKE 工作空间</strong><small>智能制造管理平台</small></span>
      </div>
      <button type="button" class="sm-sidebar-collapse" :class="{ 'is-collapsed': collapsed }" :aria-label="collapsed ? '展开侧边栏' : '收起侧边栏'" :title="collapsed ? '展开侧边栏' : '收起侧边栏'" @click="emit('update:collapsed', !collapsed)"><AppIcon name="sidebar-collapse" /></button>
    </div>
    <nav ref="navigation" aria-label="主导航">
      <section v-for="group in displayGroups" :key="group.label" class="sm-nav-group">
        <button type="button" class="sm-nav-heading" :class="{ 'is-expanded': !collapsedGroups.has(group.label), 'has-active-child': group.items.some(item => item.key === active || hasActiveChild(item)) }" :title="group.label" :aria-label="group.label" :aria-controls="`sidebar-group-${group.id}`" :aria-expanded="!collapsedGroups.has(group.label)" @click="toggleGroup(group.label)">
          <AppIcon :name="group.icon" class="sm-group-icon" /><span class="sm-nav-label">{{ group.label }}</span><small class="sm-nav-count">{{ group.items.length }}</small><span class="sm-chevron-surface" aria-hidden="true"><AppIcon name="chevron" class="sm-nav-chevron" :class="{ 'is-expanded': !collapsedGroups.has(group.label) }" /></span>
        </button>
        <div v-show="!collapsedGroups.has(group.label)" :id="`sidebar-group-${group.id}`" class="sm-nav-items">
          <div v-for="item in group.items" :key="item.key" class="sm-nav-entry" :class="{ 'is-directory': item.children?.length && !item.path }">
            <div class="sm-nav-parent-row" :class="{ 'is-active': active === item.key, 'has-active-child': hasActiveChild(item), 'is-expanded': item.children?.length && expandedSubmenus.has(item.key) }">
              <button type="button" class="sm-nav-link" :class="{ 'is-active': active === item.key }" :title="item.label" :aria-label="item.label" :aria-current="active === item.key ? 'page' : undefined" :aria-expanded="item.children?.length && !item.path ? expandedSubmenus.has(item.key) : undefined" :aria-controls="item.children?.length && !item.path ? `sidebar-submenu-${item.key}` : undefined" @click="item.children?.length && !item.path ? toggleSubmenu(item.key) : emit('navigate', item.key)">
                <AppIcon :name="item.icon ?? item.key" /><span class="sm-nav-label">{{ item.label }}</span><span v-if="item.children?.length && !item.path" class="sm-chevron-surface" aria-hidden="true"><AppIcon name="chevron" class="sm-nav-chevron" :class="{ 'is-expanded': expandedSubmenus.has(item.key) }" /></span>
              </button>
              <button v-if="item.children?.length && item.path" type="button" class="sm-submenu-toggle" :title="`${expandedSubmenus.has(item.key) ? '收起' : '展开'}${item.label}子菜单`" :aria-label="`${expandedSubmenus.has(item.key) ? '收起' : '展开'}${item.label}子菜单`" :aria-controls="`sidebar-submenu-${item.key}`" :aria-expanded="expandedSubmenus.has(item.key)" @click="toggleSubmenu(item.key)"><AppIcon name="chevron" class="sm-nav-chevron" :class="{ 'is-expanded': expandedSubmenus.has(item.key) }" /></button>
            </div>
            <div v-if="item.children?.length" v-show="expandedSubmenus.has(item.key)" :id="`sidebar-submenu-${item.key}`" class="sm-nav-children">
              <button v-for="child in item.children" :key="child.key" type="button" class="sm-nav-link sm-nav-child" :class="{ 'is-active': active === child.key }" :title="child.label" :aria-label="child.label" :aria-current="active === child.key ? 'page' : undefined" @click="emit('navigate', child.key)"><AppIcon :name="child.icon ?? child.key" /><span class="sm-nav-label">{{ child.label }}</span></button>
            </div>
          </div>
        </div>
      </section>
    </nav>
    <footer class="workspace-sidebar-footer">
      <button type="button" class="workspace-logout" title="退出登录" aria-label="退出登录" @click="emit('logout')"><AppIcon name="logout" /><span class="sm-nav-label">退出登录</span></button>
    </footer>
  </aside>
</template>

<style scoped>
.sm-sidebar.sm-sidebar {
  --nav-text: #bfd0e4;
  --nav-icon: #9cb6d3;
  --nav-hover: #193e63;
  --nav-selected: #1677ff;
  padding: 0 clamp(.4rem, 1vw, 1rem);
  background: #0b2948;
  overflow: visible;
}
.sm-sidebar-top { position: relative; display: flex; align-items: center; flex-shrink: 0; min-height: 5rem; margin-bottom: .75rem; border-bottom: 1px solid #ffffff12; }
.workspace-brand { display: flex; align-items: center; gap: .65rem; min-width: 0; padding-right: 1.65rem; }
.workspace-brand-mark { display: grid; place-items: center; flex: 0 0 2.25rem; height: 2.25rem; border-radius: .6rem; background: #1677ff; color: #fff; font-size: 1.55rem; font-weight: 800; font-style: italic; }
.workspace-brand-copy { min-width: 0; display: grid; gap: .3rem; }
.workspace-brand-copy strong { color: #fff; font-size: clamp(.8rem, .9vw, .95rem); white-space: nowrap; }
.workspace-brand-copy small { color: #8faac6; font-size: .65rem; white-space: nowrap; }
.sm-sidebar-collapse { position: absolute; right: -.4rem; top: 1.7rem; z-index: 2; display: grid; place-items: center; width: 1.65rem; height: 1.65rem; padding: 0; border: 0; border-radius: .4rem; background: #ffffff0c; color: #a9c6e4; transition: background-color 160ms ease; }
.sm-sidebar-collapse:hover { background: #254b70; color: #fff; }
.sm-sidebar-collapse .app-icon { width: 1rem; height: 1rem; transition: transform 180ms ease; }
.sm-sidebar-collapse.is-collapsed .app-icon { transform: rotate(180deg); }
.sm-sidebar nav { flex: 1; min-height: 0; overflow-x: hidden; overflow-y: auto; overscroll-behavior-y: contain; margin-right: -.4rem; padding: 0 .4rem .75rem 0; scrollbar-width: thin; scrollbar-color: #345576 transparent; }
.sm-sidebar .sm-nav-group { margin-bottom: 1rem; }
.sm-nav-heading { display: flex; align-items: center; width: 100%; min-height: 2.75rem; padding: .5rem .6rem; gap: .6rem; border: 0; border-radius: .45rem; background: transparent; color: #c8d7e8; font-size: .8rem; font-weight: 600; text-align: left; }
.sm-nav-heading.is-expanded { background: #ffffff06; }
.sm-nav-heading.has-active-child { color: #fff; background: #193b5d; }
.sm-nav-heading:hover { background: var(--nav-hover); color: #fff; }
.sm-group-icon { width: 1.125rem; height: 1.125rem; flex-shrink: 0; color: var(--nav-icon); }
.sm-nav-heading > .sm-nav-label { flex: 1; }
.sm-nav-count { display: grid; place-items: center; min-width: 1.1rem; height: 1.1rem; padding: 0 .25rem; border-radius: .35rem; background: #ffffff0c; color: #9bb5d0; font-size: .6rem; font-variant-numeric: tabular-nums; }
.sm-nav-items { padding: .4rem 0 0 .5rem; }
.sm-nav-entry + .sm-nav-entry { margin-top: .15rem; }
.sm-nav-parent-row { display: flex; align-items: center; border: 0; border-radius: .45rem; }
.sm-nav-parent-row.has-active-child { background: #ffffff06; }
.sm-nav-parent-row:hover { background: var(--nav-hover); }
.sm-sidebar .sm-nav-link { display: flex; align-items: center; gap: .65rem; width: 100%; min-width: 0; min-height: 2.6rem; margin: 0; padding: .5rem .6rem; border: 0; border-radius: .45rem; background: transparent; color: var(--nav-text); font-size: .8rem; line-height: 1.4; text-align: left; box-shadow: none; transition: background-color 160ms ease, color 160ms ease; }
.sm-nav-label { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sm-nav-link > .app-icon { width: 1.1rem; height: 1.1rem; flex-shrink: 0; color: var(--nav-icon); }
.sm-nav-link:hover { color: #fff; }
.sm-submenu-toggle { display: grid; place-items: center; align-self: stretch; flex: 0 0 1.65rem; width: 1.65rem; height: auto; padding: 0; border: 0; border-radius: .4rem; background: transparent; color: var(--nav-icon); }
.sm-submenu-toggle:hover { background: #ffffff10; color: #fff; }
.sm-chevron-surface { display: grid; place-items: center; flex: 0 0 1rem; width: 1rem; height: 1rem; color: #9eb6cf; }
.sm-nav-link > .sm-chevron-surface { margin-left: auto; }
.sm-nav-chevron { width: .85rem; height: .85rem; transition: transform 180ms ease; }
.sm-nav-chevron.is-expanded { transform: rotate(90deg); }
.sm-nav-children { display: grid; gap: .15rem; margin: .3rem 0 .5rem 1.05rem; padding-left: .65rem; border-left: 1px solid #ffffff1a; }
.sm-sidebar .sm-nav-child { min-height: 2.3rem; font-size: .75rem; }
.sm-nav-child > .app-icon { width: .95rem; height: .95rem; }
.sm-sidebar .sm-nav-child:hover { background: var(--nav-hover); }
.sm-sidebar .sm-nav-parent-row.is-active, .sm-sidebar .sm-nav-child.is-active { background: var(--nav-selected); color: #fff; box-shadow: none; }
.sm-sidebar .sm-nav-parent-row.is-active > .sm-nav-link { background: transparent; color: #fff; }
.sm-sidebar .sm-nav-link.is-active > .app-icon, .sm-nav-parent-row.is-active > .sm-submenu-toggle { color: #fff; }
.sm-sidebar button:focus-visible { outline: 2px solid #8ac4ff; outline-offset: -2px; }
.workspace-sidebar-footer { flex-shrink: 0; padding: .8rem 0; border-top: 1px solid #ffffff1a; }
.workspace-logout { display: flex; align-items: center; gap: .7rem; width: 100%; min-height: 2.75rem; padding: .6rem; border: 0; border-radius: .45rem; background: transparent; color: #dce7f3; font-size: .8rem; text-align: left; }
.workspace-logout .app-icon { flex-shrink: 0; width: 1.15rem; height: 1.15rem; color: #f49886; }
.workspace-logout:hover { background: #ffffff0d; color: #fff; }
.sm-sidebar.is-sidebar-collapsed { padding-inline: clamp(.4rem, .65vw, .625rem); }
.is-sidebar-collapsed .workspace-brand { padding: 0; margin: auto; }
.is-sidebar-collapsed .workspace-brand-copy, .is-sidebar-collapsed .sm-nav-label, .is-sidebar-collapsed .sm-nav-count, .is-sidebar-collapsed .sm-chevron-surface { display: none; }
.is-sidebar-collapsed .sm-sidebar-collapse { right: -1.4rem; background: #193e63; }
.is-sidebar-collapsed .sm-nav-heading, .is-sidebar-collapsed .sm-nav-link, .is-sidebar-collapsed .workspace-logout { justify-content: center; padding-inline: 0; gap: 0; }
.is-sidebar-collapsed .sm-nav-items, .is-sidebar-collapsed .sm-nav-children { margin-left: 0; padding-left: 0; border: 0; }
.is-sidebar-collapsed .sm-nav-parent-row { position: relative; }
.is-sidebar-collapsed .sm-submenu-toggle { position: absolute; right: -.25rem; bottom: 0; width: .8rem; height: .9rem; background: #193e63; }
.is-sidebar-collapsed .sm-submenu-toggle .app-icon { width: .65rem; height: .65rem; }
@media (max-width: 760px) {
  .sm-sidebar.sm-sidebar { padding-inline: clamp(.4rem, 1.8vw, .55rem); }
  .sm-sidebar-top { min-height: 4.5rem; }
  .workspace-brand { padding: 0; margin: auto; }
  .workspace-brand-copy, .sm-nav-label, .sm-nav-count, .sm-chevron-surface, .sm-sidebar-collapse { display: none; }
  .sm-nav-heading, .sm-sidebar .sm-nav-link, .workspace-logout { justify-content: center; padding-inline: 0; gap: 0; }
  .sm-nav-items, .sm-nav-children { margin-left: 0; padding-left: 0; border: 0; }
  .sm-nav-parent-row { position: relative; }
  .sm-submenu-toggle { position: absolute; right: -.25rem; bottom: 0; width: .8rem; height: .9rem; background: #193e63; }
  .sm-submenu-toggle .app-icon { width: .65rem; height: .65rem; }
}
@media (prefers-reduced-motion: reduce) { .sm-sidebar-collapse, .sm-sidebar-collapse .app-icon, .sm-nav-link, .sm-nav-chevron { transition: none; } }
</style>
