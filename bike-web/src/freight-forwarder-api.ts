import api from './api'

export type FreightForwarderFields = {
  name: string
  contactName: string
  phone: string
  address: string
  remark: string | null
}

export type FreightForwarder = FreightForwarderFields & { id: number; revision: number }

const basePath = '/api/freight-forwarders'

export const freightForwarderApi = {
  async list() {
    return (await api.get<{ data: FreightForwarder[] }>(basePath)).data.data
  },
  async create(forwarder: FreightForwarderFields) {
    return (await api.post<{ data: FreightForwarder }>(basePath, forwarder)).data.data
  },
  async update(id: number, revision: number, forwarder: FreightForwarderFields) {
    return (await api.put<{ data: FreightForwarder }>(basePath + '/' + id, { revision, forwarder })).data.data
  },
}
