import { computed, inject, onBeforeUnmount, provide, ref, watch, type InjectionKey } from 'vue'
import api from './api'

export const DEFAULT_DECORATION = '/images/yellow-companion.png'
type Decoration = { imageDataUrl: string | null }
type DecorationResponse = { data: Decoration }

function createAccountDecoration(username: () => string) {
  const savedImage = ref<string | null>(null)
  const loading = ref(false)
  const loadError = ref('')
  let accountVersion = 0
  let loadVersion = 0

  async function reload() {
    const account = accountVersion
    const request = ++loadVersion
    loading.value = true
    loadError.value = ''
    try {
      const response = await api.get<DecorationResponse>('/api/auth/decoration')
      if (account === accountVersion && request === loadVersion) savedImage.value = response.data.data.imageDataUrl
    } catch {
      if (account === accountVersion && request === loadVersion) loadError.value = '装饰图片加载失败，请重试。'
    } finally {
      if (account === accountVersion && request === loadVersion) loading.value = false
    }
  }

  async function save(file: File | null) {
    const account = accountVersion
    // 防止尚未完成的读取在保存之后把旧图片覆盖回来。
    ++loadVersion
    loading.value = false
    let response
    if (file) {
      const form = new FormData()
      form.append('file', file)
      response = await api.put<DecorationResponse>('/api/auth/decoration', form)
    } else {
      response = await api.delete<DecorationResponse>('/api/auth/decoration')
    }
    if (account !== accountVersion) return false
    savedImage.value = response.data.data.imageDataUrl
    loadError.value = ''
    return true
  }

  watch(username, () => {
    ++accountVersion
    savedImage.value = null
    void reload()
  }, { immediate: true })
  onBeforeUnmount(() => { ++accountVersion; ++loadVersion })
  return { savedImage, imageUrl: computed(() => savedImage.value || DEFAULT_DECORATION), loading, loadError, reload, save }
}

const decorationKey: InjectionKey<ReturnType<typeof createAccountDecoration>> = Symbol('account-decoration')

export function provideAccountDecoration(username: () => string) {
  provide(decorationKey, createAccountDecoration(username))
}

export function useAccountDecoration() {
  return inject(decorationKey, null)
}
