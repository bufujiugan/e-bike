<script setup lang="ts">
import { computed, ref } from 'vue'
import Sidebar from './Sidebar.vue'
import TopHeader from './TopHeader.vue'
import PageHero from '../common/PageHero.vue'
import { pagePresentation, type NavGroup } from '../../config/page-presentation'
import { provideAccountDecoration } from '../../account-decoration'
import { tr } from '../../i18n/language'
const props = defineProps<{ groups: NavGroup[]; active: string; title: string; group: string; username: string; role: string; lastLoaded: string; isCustomer: boolean }>()
provideAccountDecoration(() => props.username)
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
  <main class="admin-app sm-app sm-unified" :class="{ 'cc-app': active === 'dashboard', 'pc-app': isProductionView, 'pc-module-app': isProductionView && active !== 'production', 'is-sidebar-collapsed': sidebarCollapsed }">
    <div class="admin-shell sm-shell">
      <Sidebar :groups="groups" :active="active" :username="username" :role="tr(role)" :collapsed="sidebarCollapsed"
        @update:collapsed="updateSidebarCollapsed" @navigate="emit('navigate', $event)" @logout="emit('logout')" />
      <section class="workspace sm-workspace">
        <TopHeader :groups="groups" :username="username" :role="tr(role)" :is-customer="isCustomer" :last-loaded="lastLoaded"
          :title="tr(presentation?.title ?? title)" :group="tr(group)"
          @navigate="emit('navigate', $event)" @guide="emit('guide')" @settings="emit('settings')" @logout="emit('logout')">
          <template #notifications><slot name="notifications" /></template>
        </TopHeader>
        <PageHero v-if="active !== 'customer-orders' && active !== 'dashboard' && active !== 'workflow' && active !== 'production-tasks'"
          :workshop="isProductionView" :title="tr(presentation?.title ?? title)"
          :description="tr(presentation?.description ?? '业务协同 · 数据透明 · 高效运营')"
          :theme="presentation?.theme ?? 'production'" :motto="tr(presentation?.motto ?? '智能制造生产运营系统')" :group="tr(group)" />
        <slot />
      </section>
    </div>
    <slot name="dialogs" />
  </main>
</template>

<style scoped>
.sm-app.is-sidebar-collapsed { --sm-sidebar: var(--sm-sidebar-collapsed); }
</style>
