import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';
import { apiProxy } from './vite.proxy.ts';

const proxy = apiProxy(process.env);

export default defineConfig({
  plugins: [react()],
  server: { host: 'localhost', port: 5173, strictPort: true, proxy },
  preview: { host: 'localhost', proxy },
  test: {
    environment: 'jsdom',
    globals: false,
    setupFiles: ['./src/test/setup.ts'],
  },
});
