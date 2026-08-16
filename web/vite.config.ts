import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // The browser talks to the API through the same origin in development, so the
    // deployed setup (one reverse proxy in front of both) is also what is exercised
    // locally. Cross-origin only in dev would mean CORS bugs discovered in production.
    proxy: {
      '/api': {
        target: process.env.ARCHGRID_API_URL ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
})
