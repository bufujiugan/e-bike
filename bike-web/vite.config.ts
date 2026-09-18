import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    host: '0.0.0.0',
    proxy: {
      '/api': 'http://localhost:18080',
      '/v3': 'http://localhost:18080',
      '/swagger-ui': 'http://localhost:18080',
      '/actuator': 'http://localhost:18080',
    },
  },
})
