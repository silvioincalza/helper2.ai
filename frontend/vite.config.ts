/// <reference types="vitest/config" />
import { fileURLToPath } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';
import pkg from './package.json' with { type: 'json' };

const target = 'http://localhost:8080';

/** Pages that routes.tsx loads with react-router `lazy`: every other module ships in the one entry chunk. */
const LAZY_PAGE = /[\\/]src[\\/]pages[\\/](?!HomePage)\w+\.tsx$/;

export default defineConfig({
  plugins: [react()],
  // One version for the whole release (ADR 0029): package.json is the frontend's copy of it.
  define: { __APP_VERSION__: JSON.stringify(pkg.version) },
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  build: {
    // Rolldown's default splits modules shared by the home page and a lazy page into small chunks the entry
    // preloads anyway: more requests and more bytes. One group keeps all of them in the entry.
    rolldownOptions: { output: { codeSplitting: { groups: [{ name: 'index', test: (id) => !LAZY_PAGE.test(id) }] } } },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': target,
      '/oauth2': target,
      '/login': target,
      '/logout': target,
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    // A shell that exports NODE_ENV=production would load React's production build, which has no act().
    env: { NODE_ENV: 'test' },
  },
});
