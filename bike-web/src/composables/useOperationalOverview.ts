import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import api from '../api'
import { hasPermission, hasRole, type PermissionContext } from '../permissions'
import type { TrendPoint } from '../components/charts/TrendCard.vue'
type SummaryMetric = { label: string; value: number; unit: string; tone: string }
export function useOperationalOverview(context: Ref<PermissionContext | null>, module: Ref<string>) {
  const points = ref<TrendPoint[] | null>(null)
  const metrics = ref<SummaryMetric[] | null>(null)
  const error = ref(''), loading = ref(false)
  let version = 0
  const permitted = computed(() => !hasRole(context.value, 'WEB_AGENT') && !hasRole(context.value, 'PDA_OPERATOR') && hasPermission(context.value, 'DASHBOARD_READ'))
  async function refresh() {
    const request = ++version
    points.value = null; metrics.value = null; error.value = ''; loading.value = false
    if (!permitted.value || !['assembly', 'inventory', 'stock-in', 'stock-out', 'stock'].includes(module.value)) return
    loading.value = true
    try {
      // 复用现有看板读取协议；不将受限账号的列表权限扩展为看板权限。
      const responses = await Promise.allSettled([api.get('/api/dashboard/summary'), api.get('/api/dashboard/trend?days=7')])
      if (request !== version) return
      for (const [index, response] of responses.entries()) {
        if (response.status === 'fulfilled') {
          if (index === 0) metrics.value = response.value.data.data.metrics
          else points.value = response.value.data.data.points
        } else {
          error.value = api.isAxiosError(response.reason) ? response.reason.response?.data?.message ?? '运营统计加载失败，请重试' : '运营统计加载失败，请重试'
        }
      }
    } finally { if (request === version) loading.value = false }
  }
  watch([permitted, module], refresh, { immediate: true })
  onBeforeUnmount(() => { version++ })
  function value(label: string) { return metrics.value?.find(metric => metric.label === label)?.value ?? null }
  return { points, metrics, permitted, error, loading, refresh, value }
}
