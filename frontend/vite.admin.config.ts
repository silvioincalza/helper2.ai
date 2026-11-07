import { fileURLToPath } from 'node:url';
import { defineConfig, mergeConfig, type Plugin } from 'vite';
import base from './vite.config';

/** The back office app on a developer's machine (its dev profile); the public app stays on 8080. */
const backOffice = 'http://localhost:8090';

/** In development, every back office address opens the admin entry, as the back office app does in a deploy. */
function adminFallback(): Plugin {
  return {
    name: 'helper2-admin-fallback',
    apply: 'serve',
    configureServer: (server) => {
      server.middlewares.use((req, _res, next) => {
        const accept = req.headers.accept ?? '';
        if (req.url?.startsWith('/admin') && accept.includes('text/html'))
          req.url = '/admin/admin.html';
        next();
      });
    },
  };
}

/**
 * The back office web app (ADR 0069): built apart from the public app, into dist-admin/, under /admin/ so its files
 * never share a path with the public assets. The back office app serves it on the admin host only.
 */
export default mergeConfig(
  base,
  defineConfig({
    base: '/admin/',
    plugins: [adminFallback()],
    server: {
      port: 5174,
      proxy: {
        '/api': backOffice,
        '/oauth2': backOffice,
        '/login': backOffice,
        '/logout': backOffice,
      },
    },
    build: {
      outDir: 'dist-admin',
      emptyOutDir: true,
      rollupOptions: { input: fileURLToPath(new URL('./admin.html', import.meta.url)) },
    },
  }),
);
