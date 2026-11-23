import { ref, watch } from 'vue'
import { Locale } from 'vant'
import enUS from 'vant/es/locale/lang/en-US'
import zhCN from 'vant/es/locale/lang/zh-CN'
import english from './en.json'

const storageKey = 'bike_language'
export const language = ref<'zh-CN' | 'en'>(localStorage.getItem(storageKey) === 'en' ? 'en' : 'zh-CN')
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
  localStorage.setItem(storageKey, value)
  if (value === 'en') Locale.use('en-US', enUS)
  else Locale.use('zh-CN', zhCN)
}, { immediate: true, flush: 'sync' })
