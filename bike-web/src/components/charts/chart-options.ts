import type { EChartsCoreOption } from 'echarts/core'
export type ChartValue = { name: string; value: number }
export function barOption(values: ChartValue[]): EChartsCoreOption | null {
  if (!values.length || !values.some(item => item.value > 0)) return null
  return { tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } }, grid: { left: 80, top: 5, right: 36, bottom: 8 }, xAxis: { type: 'value', show: false }, yAxis: { type: 'category', inverse: true, data: values.map(item => item.name), axisLine: { show: false }, axisTick: { show: false }, axisLabel: { color: '#7b6153', width: 76, overflow: 'truncate', fontSize: 12 } }, series: [{ type: 'bar', barMaxWidth: 14, showBackground: true, backgroundStyle: { color: '#fff0df', borderRadius: 3 }, data: values.map(item => item.value), label: { show: true, position: 'right', color: '#7b6153', fontSize: 12 }, itemStyle: { color: '#b44e36', borderRadius: 3 } }] }
}
export function donutOption(values: ChartValue[], centerLabel: string): EChartsCoreOption | null {
  const positive = values.filter(item => item.value > 0)
  if (!positive.length) return null
  const total = positive.reduce((sum, item) => sum + item.value, 0)
  return { tooltip: { trigger: 'item', renderMode: 'richText' }, legend: { orient: 'vertical', right: 0, top: 'middle', itemWidth: 8, itemHeight: 8, icon: 'circle', textStyle: { fontSize: 12, color: '#7b6153', width: 116, overflow: 'truncate' } }, series: [{ type: 'pie', radius: ['55%', '80%'], center: ['29%', '50%'], avoidLabelOverlap: true, label: { show: true, position: 'center', formatter: () => `${total.toLocaleString('zh-CN')}\n${centerLabel}`, lineHeight: 22, fontSize: 12, color: '#7b6153' }, emphasis: { scale: false }, data: positive }] }
}
