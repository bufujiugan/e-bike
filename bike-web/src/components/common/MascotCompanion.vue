<script setup lang="ts">
import { DEFAULT_DECORATION, useAccountDecoration } from '../../account-decoration'
withDefaults(defineProps<{ compact?: boolean }>(), { compact: false })
const decoration = useAccountDecoration()
</script>

<template>
  <span class="mascot-companion" :class="{ 'is-compact': compact }" aria-hidden="true">
    <span class="mascot-halo" />
    <span class="mascot-spark mascot-spark-one" />
    <span class="mascot-spark mascot-spark-two" />
    <img :src="decoration?.imageUrl.value || DEFAULT_DECORATION" alt="" width="140" height="140" draggable="false" />
    <span class="mascot-platform" />
  </span>
</template>

<style scoped>
.mascot-companion { position: relative; display: block; width: 160px; height: 160px; flex: none; pointer-events: none; }
.mascot-halo { position: absolute; inset: 11px 8px 6px; border-radius: 50%; background: #fff0b9; box-shadow: inset 0 0 0 9px #fff8de; }
.mascot-companion img { position: absolute; width: 140px; height: 140px; left: 10px; bottom: 9px; object-fit: contain; filter: drop-shadow(0 5px 3px #9d752018); }
.mascot-platform { position: absolute; bottom: 6px; left: 24px; right: 24px; height: 10px; border-radius: 50%; background: #f2d87f; box-shadow: 0 4px 0 #fff6d7; }
.mascot-spark { position: absolute; z-index: 1; width: 12px; height: 12px; background: #9f82c9; clip-path: polygon(50% 0, 63% 37%, 100% 50%, 63% 63%, 50% 100%, 37% 63%, 0 50%, 37% 37%); }
.mascot-spark-one { left: 3px; top: 30px; transform: rotate(15deg); }
.mascot-spark-two { right: 0; top: 64px; width: 9px; height: 9px; background: #d49a46; }
.is-compact { width: 64px; height: 70px; }
.is-compact .mascot-halo { inset: 8px 0 4px; box-shadow: none; }
.is-compact img { width: 64px; height: 64px; left: 0; bottom: 5px; }
.is-compact .mascot-platform { left: 9px; right: 9px; height: 5px; bottom: 3px; }
.is-compact .mascot-spark { display: none; }
</style>
