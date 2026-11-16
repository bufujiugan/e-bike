import api from './api'
import type { Money } from './pi-api'

export type FactorySalesDraft = {
  requestId: string; revision: number; customerId: number | null; warehouseId: number | null
  businessType: 'FACTORY_DOMESTIC' | 'FACTORY_EXPORT'; currencyCode: string
  factoryName: string; contractNo: string; goodsDescription: string
  netAmount: Money | null; vatAmount: Money | null; paymentTerms: string; paymentDueDate: string | null
  pickupDate: string | null; pickupAddress: string; pickupContact: string
  preparationNote: string; deliveryNote: string; collectionNote: string; remark: string; orderIds: number[]
}
export type FactoryStep = 'CONTRACT' | 'PREPARATION' | 'COLLECTION' | 'PICKUP' | 'CLOSED'
export const factorySteps: { code: FactoryStep; name: string }[] = [
  { code: 'CONTRACT', name: '合同与交易资料' }, { code: 'PREPARATION', name: '备货跟进' },
  { code: 'COLLECTION', name: '确认工厂已收款' }, { code: 'PICKUP', name: '提货与交接' }, { code: 'CLOSED', name: '办理结案' },
]
export type FactorySalesOrder = {
  id: number; orderNo: string; customerName: string; warehouseName: string | null; revision: number
  details: FactorySalesDraft; steps: Partial<Record<FactoryStep, { operatorName: string; confirmedAt: string; note: string | null }>>
  orders: { id: number; orderNo: string; orderQty: number; shippedQty: number; status: string }[]
  createdAt: string; updatedAt: string
}
export type SettlementDraft = { requestId: string; amount: Money; currencyCode: string; occurredAt: string; counterparty: string; referenceNo: string; remark: string }
export type SettlementEntry = SettlementDraft & { id: number; status: 'CONFIRMED' | 'REVERSED'; createdAt: string; reversalReason: string | null }
export type SettlementType = 'PI' | 'FACTORY'
export const europeSalesApi = {
  async list(params: { keyword?: string; businessType?: string; warehouseId?: number; page: number; pageSize: number }) { return (await api.get<{ data: { records: FactorySalesOrder[]; total: number } }>('/api/europe/factory-orders', { params })).data.data },
  async get(id: number) { return (await api.get<{ data: FactorySalesOrder }>(`/api/europe/factory-orders/${id}`)).data.data },
  async save(id: number | undefined, body: FactorySalesDraft) { return (await api.request<{ data: FactorySalesOrder }>({ method: id ? 'PUT' : 'POST', url: `/api/europe/factory-orders${id ? '/' + id : ''}`, data: body })).data.data },
  async step(id: number, code: FactoryStep, revision: number, completed: boolean, note: string) { return (await api.post<{ data: FactorySalesOrder }>(`/api/europe/factory-orders/${id}/steps/${code}`, { revision, completed, note })).data.data },
  async settlements(type: SettlementType, id: number) { return (await api.get<{ data: SettlementEntry[] }>(`/api/europe/settlements/${type}/${id}`)).data.data },
  async record(type: SettlementType, id: number, body: SettlementDraft) { return (await api.post<{ data: SettlementEntry[] }>(`/api/europe/settlements/${type}/${id}`, body)).data.data },
  async supplement(type: SettlementType, id: number, entryId: number, body: Pick<SettlementDraft, 'counterparty' | 'referenceNo' | 'remark'>) { return (await api.put<{ data: SettlementEntry[] }>(`/api/europe/settlements/${type}/${id}/${entryId}/supplement`, body)).data.data },
  async reverse(type: SettlementType, id: number, entryId: number, reason: string) { return (await api.post<{ data: SettlementEntry[] }>(`/api/europe/settlements/${type}/${id}/${entryId}/reverse`, { reason })).data.data },
}
