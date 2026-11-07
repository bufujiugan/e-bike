import api from './api'
export type FulfillmentRules = {
  paymentMode: 'PREPAY' | 'CREDIT'; deliveryMode: 'SELLER_TRANSPORT' | 'CUSTOMER_PICKUP'
  viaForwarderWarehouse: boolean; salesContractRequired: boolean; purchaseContractRequired: boolean
  dispatchSheetRequired: boolean; preparationRequired: boolean; settlementRequired: boolean
  requiredDocuments: string[]; warehouseIds: number[]
}
export type FulfillmentProfileDraft = {
  profileCode: string; version: string; name: string; tradeType: string; collectionEntity: string
  templateCode: string; templateVersion: string; rules: FulfillmentRules; reason: string
}
export type FulfillmentProfile = Omit<FulfillmentProfileDraft, 'reason'> & { id: number; enabled: boolean; createdAt: string }
export const fulfillmentApi = {
  async list() { return (await api.get<{ data: FulfillmentProfile[] }>('/api/pi-fulfillment-profiles')).data.data },
  async publish(request: FulfillmentProfileDraft) { return (await api.post<{ data: FulfillmentProfile }>('/api/pi-fulfillment-profiles', request)).data.data },
  async availability(id: number, enabled: boolean, reason: string) { await api.put(`/api/pi-fulfillment-profiles/${id}/availability`, { enabled, reason }) },
}
