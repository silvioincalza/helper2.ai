/// <reference types="vitest/config" />
import { fileURLToPath } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig, type Plugin } from 'vite';
import pkg from './package.json' with { type: 'json' };

const target = 'http://localhost:8080';

/** The Latin files of the two variable fonts (ADR 0028): the only font files an English page needs. */
const PRELOADED_FONTS = /^assets\/geist(-mono)?-latin-wght-normal-[\w-]+\.woff2$/;

/**
 * Preloads the Latin Geist and Geist Mono files, so the text does not wait for the CSS to name them. Vite emits the
 * fonts with a hashed name, so the links are added after the build, from the bundle. Same origin: nothing for the
 * CSP to allow (font-src 'self').
 */
function preloadFonts(): Plugin {
  // The public app builds under /, the back office web app under /admin/ (vite.admin.config.ts).
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
  // One version for the whole release (ADR 0034): package.json is the frontend's copy of it.
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
    // A shell that exports NODE_ENV=production would load React's production build, which has no act().
    env: { NODE_ENV: 'test' },
  },
});
