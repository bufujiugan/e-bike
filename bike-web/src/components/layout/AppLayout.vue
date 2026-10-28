<script setup lang="ts">
import { computed, ref } from 'vue'
import Sidebar from './Sidebar.vue'
import TopHeader from './TopHeader.vue'
import PageHero from '../common/PageHero.vue'
import { pagePresentation, type NavGroup } from '../../config/page-presentation'
const props = defineProps<{ groups: NavGroup[]; active: string; title: string; group: string; username: string; role: string; lastLoaded: string }>()
const emit = defineEmits<{ navigate: [key: string]; guide: []; settings: []; logout: [] }>()
const presentation = computed(() => pagePresentation[props.active])
const isProductionView = computed(() => props.active === 'production' || props.active.startsWith('production-'))
const sidebarCollapsed = ref(localStorage.getItem('bike_sidebar_collapsed') === 'true')
function updateSidebarCollapsed(collapsed: boolean) {
  sidebarCollapsed.value = collapsed
  localStorage.setItem('bike_sidebar_collapsed', String(collapsed))
}
</script>
<template>
  <main class="admin-app sm-app sm-unified" :class="{ 'cc-app': active === 'dashboard', 'pc-app': isProductionView, 'pc-module-app': isProductionView && active !== 'production', 'is-sidebar-collapsed': sidebarCollapsed }"><div class="admin-shell sm-shell"><Sidebar :groups="groups" :active="active" :username="username" :role="role" :collapsed="sidebarCollapsed" @update:collapsed="updateSidebarCollapsed" @navigate="emit('navigate', $event)" @logout="emit('logout')" /><section class="workspace sm-workspace"><TopHeader :groups="groups" :username="username" :role="role" :last-loaded="lastLoaded" :title="presentation?.title ?? title" :group="group" @navigate="emit('navigate', $event)" @guide="emit('guide')" @settings="emit('settings')" @logout="emit('logout')" /><PageHero v-if="active !== 'dashboard' && active !== 'workflow' && active !== 'production-tasks'" :workshop="isProductionView" :title="presentation?.title ?? title" :description="presentation?.description ?? '业务协同 · 数据透明 · 高效运营'" :theme="presentation?.theme ?? 'production'" :motto="presentation?.motto ?? '智能制造生产运营系统'" :group="group" /><slot /></section></div><slot name="dialogs" /></main>
</template>

<style scoped>
.sm-app.is-sidebar-collapsed { --sm-sidebar: var(--sm-sidebar-collapsed); }
</style>
