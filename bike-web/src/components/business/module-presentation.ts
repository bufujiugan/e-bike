import type { ModuleData } from '../../config/page-presentation'
export function cell(module: ModuleData, row: string[], label: string): string { return row[module.columns.indexOf(label)] ?? '' }
export function numeric(value: string): number { const normalized = value.replace(/,/g, '').match(/^-?\d+(?:\.\d+)?/); return normalized ? Number(normalized[0]) : 0 }
export function sumColumn(module: ModuleData, label: string): number | null {
  if (!module.columns.includes(label)) return null
  return module.rows.reduce((sum, row) => sum + numeric(cell(module, row, label)), 0)
}
export function groupedColumn(module: ModuleData, category: string, quantity: string) {
  const totals = new Map<string, number>()
  for (const row of module.rows) {
    const name = cell(module, row, category) || '未分配'
    totals.set(name, (totals.get(name) ?? 0) + numeric(cell(module, row, quantity)))
  }
  return [...totals].map(([name, value]) => ({ name, value })).sort((a, b) => b.value - a.value)
}
export function bindingProgress(value: string): number | null {
  const ratio = value.match(/^(\d+)\s*\/\s*(\d+)$/)
  return ratio && Number(ratio[2]) > 0 ? Math.min(100, Number(ratio[1]) / Number(ratio[2]) * 100) : null
}
