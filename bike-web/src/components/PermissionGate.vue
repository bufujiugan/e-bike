<script setup lang="ts">
defineProps<{ allowed: boolean }>()
</script>

<template>
  <slot v-if="allowed" />
</template>
