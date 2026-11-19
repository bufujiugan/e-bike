import type { PiDocument } from './pi-api'

export type PiSalesScenario = 'CHINA_EXPORT' | 'CZECH_DOMESTIC' | 'CZECH_EXPORT'

const scenarios = {
  CHINA_EXPORT: { title: '国内外销 · PI', salesChannel: 'CHINA_SALES', tradeType: 'EXPORT', domesticShipment: true, currencyCode: 'USD' },
  CZECH_DOMESTIC: { title: '欧洲内销 · PI', salesChannel: 'CZECH_PI', tradeType: 'DOMESTIC', domesticShipment: false, currencyCode: 'EUR' },
  CZECH_EXPORT: { title: '欧洲外销 · PI', salesChannel: 'CZECH_PI', tradeType: 'EXPORT', domesticShipment: false, currencyCode: 'EUR' },
} as const

export function salesScenarioDefaults(scenario: PiSalesScenario) { return scenarios[scenario] }
export function salesChannelLayoutName(salesChannel?: string | null) {
  if (salesChannel === 'CHINA_SALES') return '260728英国builtPI'
  if (salesChannel === 'CZECH_PI') return 'France_1104_PI'
  return undefined
}
export function salesScenarioFilters(scenario?: PiSalesScenario) {
  if (!scenario) return {}
  const { tradeType, salesChannel } = scenarios[scenario]
  return { tradeType, salesChannel }
}
export function matchesSalesScenario(form: { tradeType: string; document?: PiDocument | null }, scenario?: PiSalesScenario) {
  if (!scenario) return true
  const expected = scenarios[scenario]
  return form.tradeType === expected.tradeType && (form.document?.business?.salesChannel || 'CZECH_PI') === expected.salesChannel
}
