import { onScopeDispose, watch, type Ref } from 'vue'

export function useAutoVehicleScan(code: Ref<string>, enabled: () => boolean, scan: () => Promise<void>) {
  let timer: ReturnType<typeof setTimeout> | undefined
  function cancel() {
    if (timer !== undefined) clearTimeout(timer)
    timer = undefined
  }
  watch([code, enabled], ([value, canScan], previous) => {
    cancel()
    // 总码含完整的 32 位令牌才自动校验，避免键盘模拟扫码的半截输入被提交。
    // 校验失败后重新启用输入框时不自动重试，用户可以修改总码或点击“加入”。
    if (!canScan || value === previous?.[0] || !/^BIKE:[1-9]\d*:[a-fA-F0-9]{32}$/.test(value.trim())) return
    timer = setTimeout(() => {
      timer = undefined
      if (enabled() && code.value === value) void scan()
    }, 200)
  }, { flush: 'sync' })
  onScopeDispose(cancel)
  return { cancel }
}
