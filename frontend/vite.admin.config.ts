import { fileURLToPath } from 'node:url';
import { defineConfig, mergeConfig, type Plugin } from 'vite';
import base from './vite.config';

const backOffice = 'http://localhost:8090';

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
