import api from './api'
import type { CaseHistory } from './after-sales-api'
export type CompanyContact = { contactName: string; contactPhone: string; contactEmail: string; countryCode: string; region: string; city: string; addressLine: string; postalCode: string }
export const blankContact = (): CompanyContact => ({ contactName: '', contactPhone: '', contactEmail: '', countryCode: '', region: '', city: '', addressLine: '', postalCode: '' })
export type AfterSalesCompany = CompanyContact & { agentId: number; companyName: string; source: string; updatedAt: string | null }
export type StaffOption = { id: number; name: string }
export type CompanyMember = { id: number; username: string; displayName: string; enabled: boolean }
type Envelope<T> = { code: string; message: string; data: T }
function result<T>(response: { data: Envelope<T> }): T { if (response.data.code !== 'SUCCESS') throw new Error(response.data.message || '公司资料请求失败'); return response.data.data }
const endpoint = '/api/after-sales/companies'
export const afterSalesCompanies = {
  list: async () => result(await api.get<Envelope<AfterSalesCompany[]>>(endpoint)),
  get: async (id: number) => result(await api.get<Envelope<AfterSalesCompany>>(`${endpoint}/${id}`)),
  save: async (id: number, contact: CompanyContact) => result(await api.put<Envelope<AfterSalesCompany>>(`${endpoint}/${id}`, contact)),
  staff: async () => result(await api.get<Envelope<{ salesUsers: StaffOption[]; assignees: StaffOption[] }>>(`${endpoint}/staff-options`)),
  salespeople: async (id: number) => result(await api.get<Envelope<StaffOption[]>>(`${endpoint}/${id}/salespeople`)),
  setSalespeople: async (id: number, userIds: number[]) => result(await api.put<Envelope<StaffOption[]>>(`${endpoint}/${id}/salespeople`, { userIds })),
  members: async (id: number) => result(await api.get<Envelope<CompanyMember[]>>(`${endpoint}/${id}/members`)),
  history: async (id: number) => result(await api.get<Envelope<CaseHistory[]>>(`${endpoint}/${id}/history`)),
  assign: async (id: number, assigneeId: number | null) => result(await api.put<Envelope<unknown>>(`/api/after-sales/cases/${id}/assignee`, { assigneeId })),
}
