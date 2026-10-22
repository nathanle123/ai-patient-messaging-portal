import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In dev (`npm run dev`), /api is proxied to the platform on :8080. In Docker, nginx does the same.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8080' },
  },
});
