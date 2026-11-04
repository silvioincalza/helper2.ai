import type { RouteObject } from 'react-router';
import { Layout } from '@/components/Layout';
import { AppProviders } from '@/AppProviders';
import AccountPage from '@/pages/AccountPage';
import AdminLayout from '@/pages/admin/AdminLayout';
import AdminOverview from '@/pages/admin/AdminOverview';
import AuditPage from '@/pages/admin/AuditPage';
import ReviewQueuePage from '@/pages/admin/ReviewQueuePage';
import ScoutRunsPage from '@/pages/admin/ScoutRunsPage';
import HomePage from '@/pages/HomePage';
import MissionPage from '@/pages/MissionPage';
import NotFoundPage from '@/pages/NotFoundPage';
import PrivacyPage from '@/pages/PrivacyPage';
import ResourcePage from '@/pages/ResourcePage';
import SavedPage from '@/pages/SavedPage';
import SourcesPage from '@/pages/SourcesPage';
import TermsPage from '@/pages/TermsPage';

/** Kept separate from the router so tests can mount it in createMemoryRouter. */
export const routes: RouteObject[] = [
  {
    element: <AppProviders><Layout /></AppProviders>,
    children: [
      { index: true, element: <HomePage /> },
      // Results live on /search (ADR 0040, 3).
      { path: 'search', element: <HomePage /> },
      { path: 'resource/:id', element: <ResourcePage /> },
      { path: 'saved', element: <SavedPage /> },
      { path: 'sources', element: <SourcesPage /> },
      { path: 'account', element: <AccountPage /> },
      {
        // The back office (ADR 0023, ADR 0035): one guard, a part per route.
        path: 'admin',
        element: <AdminLayout />,
        children: [
          { index: true, element: <AdminOverview /> },
          { path: 'review', element: <ReviewQueuePage /> },
          { path: 'runs', element: <ScoutRunsPage /> },
          { path: 'audit', element: <AuditPage /> },
        ],
      },
      { path: 'mission', element: <MissionPage /> },
      { path: 'privacy', element: <PrivacyPage /> },
      { path: 'terms', element: <TermsPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
