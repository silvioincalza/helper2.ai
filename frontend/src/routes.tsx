import type { ComponentType } from 'react';
import type { RouteObject } from 'react-router';
import { Layout } from '@/components/Layout';
import { AppProviders } from '@/AppProviders';
import HomePage from '@/pages/HomePage';
import ResourcePage from '@/pages/ResourcePage';

/** A route whose page module is loaded the first time the route matches. */
const page =
  (load: () => Promise<{ default: ComponentType }>) =>
  async (): Promise<{ Component: ComponentType }> => ({
    Component: (await load()).default,
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
    children: [
      { index: true, element: <HomePage /> },
      // Results live on /search (ADR 0040, 3).
      { path: 'search', element: <HomePage /> },
      { path: 'resource/:id', element: <ResourcePage /> },
      // Few visits start on the saved, sources, account or sign-in page: they load on demand, outside the first
      // download.
      { path: 'saved', lazy: page(() => import('@/pages/SavedPage')) },
      { path: 'sources', lazy: page(() => import('@/pages/SourcesPage')) },
      { path: 'account', lazy: page(() => import('@/pages/AccountPage')) },
      { path: 'signin', lazy: page(() => import('@/pages/SignInPage')) },
      {
        // The back office (ADR 0023, ADR 0035): one guard, a part per route. Loaded on demand, so a visitor's
        // bundle never carries it.
        path: 'admin',
        lazy: page(() => import('@/pages/admin/AdminLayout')),
        children: [
          { index: true, lazy: page(() => import('@/pages/admin/AdminOverview')) },
          { path: 'review', lazy: page(() => import('@/pages/admin/ReviewQueuePage')) },
          { path: 'runs', lazy: page(() => import('@/pages/admin/ScoutRunsPage')) },
          { path: 'enrichment', lazy: page(() => import('@/pages/admin/EnrichmentQueuePage')) },
          { path: 'examples', lazy: page(() => import('@/pages/admin/ExamplesPage')) },
          { path: 'audit', lazy: page(() => import('@/pages/admin/AuditPage')) },
          { path: 'settings', lazy: page(() => import('@/pages/admin/SettingsPage')) },
        ],
      },
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
];
