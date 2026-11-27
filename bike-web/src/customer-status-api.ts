import api from './api'

export type CustomerStatusView = 'customer-orders' | 'customer-inventory' | 'customer-part-prices'
export type CustomerOrder = {
  id: number
  kind: 'ORDER' | 'PI'
  orderNo: string
  modelName: string | null
  orderQty: number
  shippedQty: number
  status: string
}
export type CustomerOrderDetail = {
  order: CustomerOrder
  logistics: { batchNo: string; carrier: string | null; trackingNo: string | null; status: string; shippedAt: string | null }[]
}
export type CustomerInventory = { modelId: number; modelName: string; quantity: number; availableQty: number }
export type CustomerLockReceipt = { lockNo: string; expireTime: string }
export type PartPrice = {
  id: number
  partNo: string
  partName: string
  sellable: boolean
  unitPrice: string | null
  currency: string | null
}
export type Page<T> = { records: T[]; page: number; pageSize: number; total: number }

export function customerStatusError(cause: unknown, fallback: string) {
  return api.isAxiosError(cause) ? cause.response?.data?.message ?? fallback : fallback
}
