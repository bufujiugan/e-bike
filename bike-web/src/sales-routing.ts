import { reactive } from 'vue'

/** 订单销售去向：由经办人在锁定转单、建单或订单办理时明确选择，选定后不可修改。 */
export type SalesType = 'DOMESTIC' | 'EXPORT' | 'EUROPE'

export const salesTypeOptions: Array<{ value: SalesType; title: string; menu: string; template: string; description: string }> = [
  { value: 'DOMESTIC', title: '内销 · 购销合同', menu: '销售订单 → 内销', template: '10.23金轮信德-广州塔威 购销合同', description: '生成内销订单，在订单办理第一步生成并核对购销合同，再确认订单。' },
  { value: 'EXPORT', title: '外销 · PI', menu: '销售订单 → 外销', template: '260728英国builtPI', description: '不直接生成订单；进入外销 PI，从这条锁定 / 订单生成 PI。' },
  { value: 'EUROPE', title: '欧洲 · PI', menu: '欧洲销售与交付 → 正常 PI 销售', template: 'France_1104_PI', description: '不直接生成订单；进入欧洲 PI，按实际出货仓从这条锁定 / 订单生成 PI。' },
]

export function salesTypeLabel(value?: string | null) {
  return salesTypeOptions.find(option => option.value === value)?.title ?? '待选类型'
}

/**
 * 跨菜单的待办意图：锁定页选择“外销 / 欧洲 PI”后，跳转到对应菜单并自动打开“从 SKU 锁定生成 PI”；
 * 订单办理选择外销 / 欧洲后，跳转并自动打开“关联已有订单”的新建 PI。只在当前页面内存中保留，消费后清除。
 */
export type SalesIntent =
  | { kind: 'lock'; salesType: 'EXPORT' | 'EUROPE'; europeTradeType?: 'DOMESTIC' | 'EXPORT'; wholeBatch?: boolean; sources: Array<{ lockId: number; lockNo: string; quantity?: number }>; customerId: number | null; customerName?: string }
  | { kind: 'order'; salesType: 'EXPORT' | 'EUROPE'; europeTradeType?: 'DOMESTIC' | 'EXPORT'; orderId: number; orderNo: string; customerId: number; customerName?: string }

const state = reactive<{ intent: SalesIntent | null }>({ intent: null })

export function setSalesIntent(intent: SalesIntent) { state.intent = intent }
export function peekSalesIntent(salesType: 'EXPORT' | 'EUROPE') { return state.intent?.salesType === salesType ? state.intent : null }
export function clearSalesIntent(intent?: SalesIntent | null) { if (!intent || state.intent === intent) state.intent = null }
export const salesIntentState = state

/** 进入对应菜单的导航键。 */
export function salesTypeNavKey(salesType: 'EXPORT' | 'EUROPE') { return salesType === 'EXPORT' ? 'orders' : 'europe-orders' }
