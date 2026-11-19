import type { PiDocument } from './pi-api'

export type PiSalesScenario = 'CHINA_EXPORT' | 'CZECH_DOMESTIC' | 'CZECH_EXPORT'

const scenarios = {
  CHINA_EXPORT: { title: '国内外销 · PI', salesChannel: 'CHINA_SALES', tradeType: 'EXPORT', domesticShipment: true, currencyCode: 'USD' },
  CZECH_DOMESTIC: { title: '捷克内销 · PI', salesChannel: 'CZECH_PI', tradeType: 'DOMESTIC', domesticShipment: false, currencyCode: 'EUR' },
  CZECH_EXPORT: { title: '捷克外销 · PI', salesChannel: 'CZECH_PI', tradeType: 'EXPORT', domesticShipment: false, currencyCode: 'EUR' },
} as const

export function salesScenarioDefaults(scenario: PiSalesScenario) { return scenarios[scenario] }
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
