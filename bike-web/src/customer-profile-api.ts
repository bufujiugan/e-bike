import api from './api'
export type CustomerProfile = {
  customerId: number; code: string; name: string; contactName?: string | null; contactPhone?: string | null; status: string
  address?: string | null; country?: string | null; email?: string | null; preferredCurrency?: string | null
  businessOwner?: string | null; revision: number; updatedAt: string
}
export const customerProfileApi = {
  async get(id: number) { return (await api.get<{ data: CustomerProfile }>(`/api/master/customers/${id}/profile`)).data.data },
  async update(id: number, body: Pick<CustomerProfile, 'address' | 'country' | 'email' | 'preferredCurrency' | 'businessOwner' | 'revision'> & { reason: string }) {
    return (await api.put<{ data: CustomerProfile }>(`/api/master/customers/${id}/profile`, body)).data.data
  },
}
