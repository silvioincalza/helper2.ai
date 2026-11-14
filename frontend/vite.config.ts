/// <reference types="vitest/config" />
import { fileURLToPath } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig, type Plugin } from 'vite';
import pkg from './package.json' with { type: 'json' };

const target = 'http://localhost:8080';

const PRELOADED_FONTS = /^assets\/geist(-mono)?-latin-wght-normal-[\w-]+\.woff2$/;

function preloadFonts(): Plugin {
  let base = '/';
  return {
    name: 'helper2-preload-fonts',
    apply: 'build',
    configResolved: (config) => {
      base = config.base;
    },
    transformIndexHtml: {
      order: 'post',
      handler: (_html, ctx) =>
        Object.keys(ctx.bundle ?? {})
          .filter((file) => PRELOADED_FONTS.test(file))
          .sort()
          .map((file) => ({
            tag: 'link',
            attrs: {
              rel: 'preload',
              href: `${base}${file}`,
              as: 'font',
              type: 'font/woff2',
              crossorigin: '',
            },
            injectTo: 'head' as const,
          })),
    },
  };
}

export default defineConfig({
  plugins: [react(), preloadFonts()],
  define: { __APP_VERSION__: JSON.stringify(pkg.version) },
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
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
    env: { NODE_ENV: 'test', TZ: 'UTC' },
  },
});
