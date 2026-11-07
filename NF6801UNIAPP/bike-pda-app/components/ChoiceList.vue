<template>
  <view class="choices"
    ><text class="label">{{ label }}</text
    ><view
      v-for="item in options"
      :key="String(item.value)"
      :class="[
        'option',
        String(value) === String(item.value) ? 'selected' : '',
      ]"
      @click="choose(item)"
      ><text class="text"
        >{{ String(value) === String(item.value) ? "● " : "○ "
        }}{{ item.text }}</text
      ></view
    ><text v-if="!options.length" class="label">暂无可选记录</text></view
  >
</template>
<script>
export default {
  props: {
    label: String,
    options: { default: () => [] },
    value: [Number, String],
    disabled: Boolean,
  },
  methods: {
    choose(item) {
      if (!this.disabled) this.$emit("input", item.value);
    },
  },
};
</script>
<style scoped>
.choices {
  margin-bottom: 20rpx;
}
.label {
  color: #52667a;
  font-size: 26rpx;
  margin-bottom: 8rpx;
}
.option {
  padding: 18rpx;
  margin-bottom: 8rpx;
  border-width: 1px;
  border-color: #dce5ed;
  border-radius: 10rpx;
}
.selected {
  background-color: #e6f2fe;
  border-color: #1767a6;
}
.text {
  color: #243b53;
  font-size: 28rpx;
}
</style>
