import api from './api'

export type AccountAvatar = { imageDataUrl: string | null }
type AvatarResponse = { code: string; message: string; data: AccountAvatar }

export const accountAvatarApi = {
  async get() {
    return (await api.get<AvatarResponse>('/api/auth/avatar')).data.data
  },
  async save(file: File) {
    const form = new FormData()
    form.append('file', file)
    return (await api.put<AvatarResponse>('/api/auth/avatar', form)).data.data
  },
  async reset() {
    return (await api.delete<AvatarResponse>('/api/auth/avatar')).data.data
  },
}
