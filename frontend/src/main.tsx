import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router';
// Design v4 (ADR 0042): Geist for running text, Geist Mono for the interface. Variable fonts, so one file per script
// covers every weight; only the Latin file loads for English text, and vite.config.ts preloads it.
import '@fontsource-variable/geist';
import '@fontsource-variable/geist-mono';
// Version 3 pages keep Space Grotesk until they move (ADR 0042, decision 8).
import '@fontsource/space-grotesk/400.css';
import '@fontsource/space-grotesk/500.css';
import '@fontsource/space-grotesk/600.css';
import './i18n';
import { routes } from './routes';
import './styles/tokens.css';
import './styles/app.css';

const router = createBrowserRouter(routes);
const root = document.getElementById('root');
if (root) {
  createRoot(root).render(
    <StrictMode>
      <RouterProvider router={router} />
    </StrictMode>,
  );
}
