import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router';
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
