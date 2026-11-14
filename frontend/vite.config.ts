import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// The production build goes to dist/; the web module's Maven build copies it into the jar's
// static/ folder, so Spring Boot serves the UI and the API from one origin.
// `npm run dev` proxies the API and the mock bank to a generator running on localhost:8080.
const generator = 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': generator,
      '/mock-bank': generator,
    },
  },
})
