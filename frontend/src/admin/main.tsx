import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router';
import '@fontsource-variable/geist';
import '@fontsource-variable/geist-mono';
import '@/i18n';
import '@/admin/adminText';
import { claimReloadedPage, followNavigation } from '@/lib/staleChunk';
import { adminRoutes } from './routes';
import '@/styles/tokens.css';
import '@/styles/app.css';

claimReloadedPage();
const router = createBrowserRouter(adminRoutes);
followNavigation(router);
const root = document.getElementById('root');
if (root) {
  createRoot(root).render(
    <StrictMode>
      <RouterProvider router={router} />
    </StrictMode>,
  );
}
