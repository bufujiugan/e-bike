import { computed, ref, watch } from 'vue'
import en from 'element-plus/es/locale/lang/en'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import english from './en.json'

export type Language = 'zh-CN' | 'en'
const storageKey = 'bike_language'
export const language = ref<Language>(localStorage.getItem(storageKey) === 'en' ? 'en' : 'zh-CN')
export const componentLocale = computed(() => language.value === 'en' ? en : zhCn)
const messages = new Map(Object.entries(english))

export function tr(source: string, values?: Record<string, unknown>): string
export function tr<T>(source: T): T extends string ? string : T
export function tr(source: unknown, values?: Record<string, unknown>): unknown {
  if (typeof source !== 'string') return source
  const message = language.value === 'en' ? messages.get(source) ?? source : source
  if (!values) return message
  return message.replace(/\{(\w+)\}/g, (placeholder, name: string) =>
    Object.prototype.hasOwnProperty.call(values, name) ? String(values[name]) : placeholder)
}

export function trInline(source: string): string {
  const text = tr(source)
  return language.value === 'en' && text !== source ? ` ${text} ` : text
}

watch(language, value => {
  document.documentElement.lang = value
  document.title = tr('E-BIKE 智慧管理系统')
  localStorage.setItem(storageKey, value)
}, { immediate: true, flush: 'sync' })

window.addEventListener('storage', event => {
  if (event.key === storageKey && (event.newValue === 'en' || event.newValue === 'zh-CN')) {
    language.value = event.newValue
  }
})
