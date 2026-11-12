import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// En desarrollo (npm run dev) la API se sirve detrás del mismo origen, como en nginx.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/salud': 'http://localhost:8080',
    },
  },
});
