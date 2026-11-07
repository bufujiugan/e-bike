export const piTemplateLayouts = [
  { value: 'STANDARD_V3', label: '标准明细表（完整费用明细）' },
  { value: 'INVOICE_V1', label: '双栏发票（卖买双方与商品明细）' },
] as const

export function isSelectablePiLayout(layout: string) {
  return ['DOMESTIC_V3', 'EXPORT_V3', ...piTemplateLayouts.map(item => item.value)].includes(layout)
}

export function piLayoutLabel(layout: string) {
  return piTemplateLayouts.find(item => item.value === layout)?.label
    || (['DOMESTIC_V3', 'EXPORT_V3'].includes(layout) ? '原有参考发票版式' : '历史版式（仅供查询）')
}
