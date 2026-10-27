import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import api from '../api'
import { hasAnyPermission, hasRole, type PermissionContext } from '../permissions'

export type DashboardTask = { id: string; skuCode: string; productName: string; batchNo?: string; status: string; createdAt: string; updatedAt: string; createdBy: string; frameSn?: string; motorSn?: string; batterySn?: string; displaySn?: string; qrCode?: string; scans?: { codeType: string; code: string; operatorName: string; scannedAt: string }[] }
export type ScanFailure = { id: number; taskId: string; code: string; codeType: string; operatorName: string; errorCode: string; reason: string; createdAt: string }
export function useProductionDashboard(context: Ref<PermissionContext | null>, date: Ref<string>) {
  const tasks = ref<DashboardTask[]>([]), total = ref<number | null>(null)
  const failures = ref<ScanFailure[]>([]), failureTotal = ref<number | null>(null)
  const loading = ref(false), taskError = ref(''), failureError = ref('')
  const permitted = computed(() => !hasRole(context.value, 'WEB_AGENT') && !hasRole(context.value, 'PDA_OPERATOR') && hasAnyPermission(context.value, ['ASSEMBLY_OPERATE', 'REWORK_OPERATE', 'VEHICLE_READ', 'PRODUCTION_MANAGE', 'STOCK_IN_OPERATE', 'STOCK_OUT_OPERATE']))
  let version = 0
  async function refresh() {
    const current = ++version
    tasks.value = []; total.value = null; failures.value = []; failureTotal.value = null
    taskError.value = ''; failureError.value = ''; loading.value = false
    if (!permitted.value) return
    loading.value = true
    const results = await Promise.allSettled([
      api.get('/api/production/tasks', { params: { page: 1, fromDate: date.value, toDate: date.value } }),
      api.get('/api/production/scan-failures', { params: { page: 1 } }),
    ])
    if (current !== version) return
    for (const [index, result] of results.entries()) {
      if (result.status === 'fulfilled') {
        const data = result.value.data.data
        if (index === 0) { tasks.value = data.records; total.value = data.total }
        else { failures.value = data.records; failureTotal.value = data.total }
      } else {
        const message = api.isAxiosError(result.reason) ? result.reason.response?.data?.message ?? '数据暂时无法加载，请重试' : '数据暂时无法加载，请重试'
        if (index === 0) taskError.value = message
        else failureError.value = message
      }
    }
    loading.value = false
  }
  watch([permitted, date], refresh, { immediate: true })
  onBeforeUnmount(() => { version++ })
  return { tasks, total, failures, failureTotal, loading, taskError, failureError, permitted, refresh }
}
