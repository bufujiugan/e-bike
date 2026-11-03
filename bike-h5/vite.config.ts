import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import legacy from '@vitejs/plugin-legacy'

export default defineConfig(({mode}) => {
const apiTarget=loadEnv(mode,'.','VITE_').VITE_API_PROXY || 'http://localhost:18080'
return {
  base: mode === 'pda' ? '/__bike_pda__/' : '/',
  plugins: [vue(), ...(mode === 'pda' ? [legacy({ targets: ['Chrome >= 53'], modernTargets: ['Chrome >= 64'] })] : [])],
  server: {
    port: 5174,
    host: '0.0.0.0',
    proxy: {
      '/api': apiTarget,
      '/v3': apiTarget,
      '/swagger-ui': apiTarget,
      '/actuator': apiTarget,
    },
  },
}})
