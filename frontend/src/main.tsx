import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router';
// Design v4 (ADR 0042): Geist for running text, Geist Mono for the interface. Variable fonts, so one file per script
// covers every weight; only the Latin file loads for English text, and vite.config.ts preloads it.
import '@fontsource-variable/geist';
import '@fontsource-variable/geist-mono';
import './i18n';
import { claimReloadedPage, followNavigation } from './lib/staleChunk';
import { routes } from './routes';
import './styles/tokens.css';
import './styles/app.css';

claimReloadedPage();
const router = createBrowserRouter(routes);
followNavigation(router);
const root = document.getElementById('root');
if (root) {
  createRoot(root).render(
    <StrictMode>
      <RouterProvider router={router} />
    </StrictMode>,
  );
}
