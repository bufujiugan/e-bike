import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({mode}) => {
const apiTarget=loadEnv(mode,'.','VITE_').VITE_API_PROXY || 'http://localhost:18080'
return {
  plugins: [vue()],
  server: {
    port: 5173,
    host: '0.0.0.0',
    proxy: {
      '/api': apiTarget,
      '/v3': apiTarget,
      '/swagger-ui': apiTarget,
      '/actuator': apiTarget,
    },
  },
}})
