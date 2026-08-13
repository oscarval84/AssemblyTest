import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * The dev server proxies `/api` to the backend so the app is same-origin in
 * development, exactly as it is in production — where Firebase Hosting rewrites
 * `/api/**` to Cloud Run. That is what keeps the session cookie `SameSite=Lax`
 * and removes CORS from the picture entirely, and it only holds if development
 * shares the property rather than working around it with permissive headers.
 *
 * Port 8085, not 8080: macOS ships an Apache on 8080.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8085',
        changeOrigin: false,
      },
    },
  },
  build: {
    // The ops console's data grid never reaches a supplier's browser.
    chunkSizeWarningLimit: 700,
  },
})
