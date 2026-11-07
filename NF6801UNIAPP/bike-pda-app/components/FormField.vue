<template>
  <view class="field"
    ><text class="label">{{ label }}</text
    ><input
      class="input"
      :value="value == null ? '' : String(value)"
      :password="password"
      :type="type"
      :maxlength="maxlength"
      :placeholder="placeholder"
      :disabled="disabled"
      @input="$emit('input', $event.detail.value)"
      @confirm="$emit('confirm')"
      @focus="$emit('focus')"
  /></view>
</template>
<script>
export default {
  props: {
    value: [String, Number],
    label: String,
    placeholder: String,
    password: Boolean,
    disabled: Boolean,
    type: { default: "text" },
    maxlength: { default: 500 },
  },
};
</script>
<style scoped>
.field {
  margin-bottom: 20rpx;
}
.label {
  font-size: 26rpx;
  color: #52667a;
  margin-bottom: 8rpx;
}
.input {
  height: 88rpx;
  font-size: 30rpx;
  padding: 16rpx;
  border-width: 1px;
  border-color: #ccd8e2;
  border-radius: 10rpx;
  background-color: #ffffff;
}
</style>
