import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8760',
        changeOrigin: true,
      },
      '/health': { target: 'http://localhost:8760', changeOrigin: true },
      '/livez': { target: 'http://localhost:8760', changeOrigin: true },
      '/readyz': { target: 'http://localhost:8760', changeOrigin: true },
      '/startupz': { target: 'http://localhost:8760', changeOrigin: true },
    },
  },
})
