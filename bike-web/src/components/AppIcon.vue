<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ name?: string }>()
const paths: Record<string, string> = {
  dashboard: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
  production: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M9 3h6l1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1z',
  vehicles: 'M7 17a4 4 0 1 0-8 0 4 4 0 0 0 8 0 M23 17a4 4 0 1 0-8 0 4 4 0 0 0 8 0 M3 17l5-9 5 9H3 M8 8h9l2 9 M6 5h4 M16 4h3',
  inventory: 'M3 8l9-5 9 5v9l-9 5-9-5z M3 8l9 5 9-5 M12 13v9 M7 5l10 6',
  orders: 'M6 3h12v18H6z M9 7h6 M9 11h6 M9 15h4',
  users: 'M16 7a4 4 0 1 0-8 0 4 4 0 0 0 8 0 M4 21v-3a8 8 0 0 1 16 0v3',
  service: 'M4 13V10a8 8 0 0 1 16 0v3 M4 11H2v7h4v-7z M20 11h2v7h-4v-7z M20 18v2h-6',
  roles: 'M12 3l8 3v6c0 5-8 9-8 9s-8-4-8-9V6z M8 12l3 3 5-6',
  trace: 'M4 9V4h5 M15 4h5v5 M20 15v5h-5 M9 20H4v-5 M8 8h3v3H8z M14 8h2v3h-2z M8 14h3v2H8z M14 14h2v2h-2z',
  search: 'M17 10a7 7 0 1 0-14 0 7 7 0 0 0 14 0 M15 15l6 6',
  arrow: 'M5 12h14 M14 7l5 5-5 5',
  plus: 'M12 5v14 M5 12h14',
  download: 'M12 3v12 M7 10l5 5 5-5 M4 15v6h16v-6',
  upload: 'M12 16V4 M7 9l5-5 5 5 M4 15v6h16v-6',
  refresh: 'M20 7v5h-5 M4 17v-5h5 M5 8a8 8 0 0 1 13-3l2 3 M4 16l2 3a8 8 0 0 0 13-3',
  layers: 'M3 7l9-5 9 5-9 5z M3 12l9 5 9-5 M3 17l9 5 9-5',
  tasks: 'M8 4H5v18h14V4h-3 M8 2h8v5H8z M8 12h8 M8 17h5',
  tag: 'M3 3h9l9 9-9 9-9-9z M7 7h.01',
  warning: 'M12 3L2 21h20z M12 9v5 M12 18h.01',
  inspect: 'M13 21H4V3h13v7 M7 7h6 M7 11h3 M19 15a4 4 0 1 0-8 0 4 4 0 0 0 8 0 M18 18l4 4',
  printer: 'M6 8V2h12v6 M6 18H3V8h18v10h-3 M6 14h12v8H6z M17 11h1',
  chart: 'M5 21V12h3v9 M11 21V7h3v14 M17 21V2h3v19',
  info: 'M22 12a10 10 0 1 0-20 0 10 10 0 0 0 20 0 M12 11v6 M12 7h.01',
}
const path = computed(() => paths[props.name ?? ''] ?? (/stock|inventory|warehouse|location|part|sku/.test(props.name ?? '') ? paths.inventory : /user|customer|agent|supplier/.test(props.name ?? '') ? paths.users : /role|config|scope/.test(props.name ?? '') ? paths.roles : /assembly|workstation|rework/.test(props.name ?? '') ? paths.production : paths.orders))
</script>
<template><svg class="app-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="path" /></svg></template>
