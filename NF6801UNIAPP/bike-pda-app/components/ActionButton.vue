<template>
  <view
    :class="[
      'button',
      secondary ? 'secondary' : '',
      disabled ? 'disabled' : '',
    ]"
    @click="click"
    ><text :class="['label', secondary ? 'dark' : '']">{{ label }}</text></view
  >
</template>
<script>
export default {
  props: { label: String, disabled: Boolean, secondary: Boolean },
  methods: {
    click() {
      if (!this.disabled) this.$emit("click");
    },
  },
};
</script>
<style scoped>
.button {
  padding: 22rpx;
  margin-bottom: 16rpx;
  background-color: #1767a6;
  border-radius: 12rpx;
  align-items: center;
}
.secondary {
  background-color: #e6eef5;
}
.disabled {
  opacity: 0.45;
}
.label {
  color: #ffffff;
  font-size: 30rpx;
}
.dark {
  color: #123454;
}
</style>
