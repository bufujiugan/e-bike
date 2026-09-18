import axios from 'axios'

/** Web 端统一 HTTP 客户端：自动携带当前会话，并集中处理认证失效。 */
const api = Object.assign(axios.create({ baseURL: '/' }), {
  isAxiosError: axios.isAxiosError,
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('bike_access_token')
  if (token && !config.headers.Authorization) config.headers.Authorization = `Bearer ${token}`
  return config
})

api.interceptors.response.use((response) => response, (error) => {
  if (error.response?.status === 401) {
    localStorage.removeItem('bike_access_token')
    window.dispatchEvent(new Event('bike-auth-expired'))
  }
  return Promise.reject(error)
})

export default api
