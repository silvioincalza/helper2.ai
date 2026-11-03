import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router';
// One font for the whole site (the redesign): Space Grotesk, three weights. Code uses the system's monospace font.
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
