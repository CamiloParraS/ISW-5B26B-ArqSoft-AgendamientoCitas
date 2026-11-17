import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Portal servido por el gateway bajo /portal/ (el prefijo se conserva).
export default defineConfig({
  base: '/portal/',
  plugins: [react()],
  build: { sourcemap: false },
});
