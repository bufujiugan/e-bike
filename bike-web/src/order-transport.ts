export const transportOptions = [
  { value: 'SELLER_ARRANGED', label: '我方安排运输' },
  { value: 'CUSTOMER_ARRANGED', label: '客户自行安排运输' },
] as const

export type TransportArrangement = typeof transportOptions[number]['value']

export function transportLabel(value?: TransportArrangement | null) {
  return transportOptions.find(option => option.value === value)?.label ?? '未登记'
}
