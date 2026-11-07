import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router';
import '@fontsource-variable/geist';
import '@fontsource-variable/geist-mono';
import '@/i18n';
import '@/pages/admin/adminText';
import { claimReloadedPage, followNavigation } from '@/lib/staleChunk';
import { adminRoutes } from './routes';
import '@/styles/tokens.css';
import '@/styles/app.css';

// The back office web app (ADR 0069): no analytics, no ads, no consent banner, none of the public pages.
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
