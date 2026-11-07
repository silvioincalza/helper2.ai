import type { ComponentType } from 'react';
import type { RouteObject } from 'react-router';
import { Layout } from '@/components/Layout';
import { AppProviders } from '@/AppProviders';
import { RouteError } from '@/components/RouteError';
import { loadChunk } from '@/lib/staleChunk';
import HomePage from '@/pages/HomePage';
import ResourcePage from '@/pages/ResourcePage';

/**
 * A route whose page module is loaded the first time the route matches. A file of an older release that is gone
 * reloads the tab once (lib/staleChunk).
 */
const page =
  (load: () => Promise<{ default: ComponentType }>) =>
  async (): Promise<{ Component: ComponentType }> => ({
    Component: (await loadChunk(load)).default,
  });

/**
 * A text page that states numbers from GET /policy (ADR 0042 decision 13): its chunk and the numbers load side by
 * side, and the page opens once both are in, or after a short wait, so it usually shows the numbers without a shift.
 * The read lives in its own chunk, outside the first download.
 */
const textPage = (load: () => Promise<{ default: ComponentType }>) =>
  page(async () => {
    const [mod] = await Promise.all([
      load(),
      import('@/hooks/usePolicy').then((p) => p.preloadPolicy()),
    ]);
    return mod;
  });

/** Kept separate from the router so tests can mount it in createMemoryRouter. */
export const routes: RouteObject[] = [
  {
    element: (
      <AppProviders>
        <Layout />
      </AppProviders>
    ),
    // Outside the layout: an error in the header or the providers.
    errorElement: <RouteError />,
    children: [
      {
        // Inside the layout: a page that cannot open keeps the header, so the visitor can go on.
        errorElement: <RouteError />,
        children: [
          { index: true, element: <HomePage /> },
          // Results live on /search (ADR 0040, 3).
          { path: 'search', element: <HomePage /> },
          // The readable address (ADR 0050): one route, so replacing a missing or old slug once the resource is
          // loaded keeps the page mounted.
          { path: 'resource/:id/:slug?', element: <ResourcePage /> },
          // Few visits start on the saved, sources, account or sign-in page: they load on demand, outside the first
          // download.
          { path: 'saved', lazy: page(() => import('@/pages/SavedPage')) },
          // The News and Events pages (v5): on demand too.
          { path: 'news', lazy: page(() => import('@/pages/NewsPage')) },
          { path: 'events', lazy: page(() => import('@/pages/EventsPage')) },
          { path: 'sources', lazy: page(() => import('@/pages/SourcesPage')) },
          { path: 'account', lazy: page(() => import('@/pages/AccountPage')) },
          { path: 'signin', lazy: page(() => import('@/pages/SignInPage')) },
          // The text pages are prose read once in a while: on demand too, so the first download stays small.
          { path: 'mission', lazy: textPage(() => import('@/pages/MissionPage')) },
          { path: 'privacy', lazy: textPage(() => import('@/pages/PrivacyPage')) },
          { path: 'terms', lazy: textPage(() => import('@/pages/TermsPage')) },
          // Few visits: loaded on demand too (ADR 0057).
          { path: 'support', lazy: page(() => import('@/pages/SupportPage')) },
          { path: 'support/thanks', lazy: page(() => import('@/pages/SupportThanksPage')) },
          { path: '*', lazy: page(() => import('@/pages/NotFoundPage')) },
        ],
      },
    ],
  },
];
