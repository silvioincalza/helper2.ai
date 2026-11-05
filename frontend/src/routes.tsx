import type { ComponentType } from 'react';
import type { RouteObject } from 'react-router';
import { Layout } from '@/components/Layout';
import { AppProviders } from '@/AppProviders';
import HomePage from '@/pages/HomePage';
import MissionPage from '@/pages/MissionPage';
import NotFoundPage from '@/pages/NotFoundPage';
import PrivacyPage from '@/pages/PrivacyPage';
import ResourcePage from '@/pages/ResourcePage';
import SavedPage from '@/pages/SavedPage';
import TermsPage from '@/pages/TermsPage';

/** A route whose page module is loaded the first time the route matches. */
const page =
  (load: () => Promise<{ default: ComponentType }>) =>
  async (): Promise<{ Component: ComponentType }> => ({
    Component: (await load()).default,
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
      { path: 'saved', element: <SavedPage /> },
      // Few visits start on the sources or the account page: they load on demand, outside the first download.
      { path: 'sources', lazy: page(() => import('@/pages/SourcesPage')) },
      { path: 'account', lazy: page(() => import('@/pages/AccountPage')) },
      {
        // The back office (ADR 0023, ADR 0035): one guard, a part per route. Loaded on demand, so a visitor's
        // bundle never carries it.
        path: 'admin',
        lazy: page(() => import('@/pages/admin/AdminLayout')),
        children: [
          { index: true, lazy: page(() => import('@/pages/admin/AdminOverview')) },
          { path: 'review', lazy: page(() => import('@/pages/admin/ReviewQueuePage')) },
          { path: 'runs', lazy: page(() => import('@/pages/admin/ScoutRunsPage')) },
          { path: 'examples', lazy: page(() => import('@/pages/admin/ExamplesPage')) },
          { path: 'audit', lazy: page(() => import('@/pages/admin/AuditPage')) },
        ],
      },
      { path: 'mission', element: <MissionPage /> },
      { path: 'privacy', element: <PrivacyPage /> },
      { path: 'terms', element: <TermsPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
