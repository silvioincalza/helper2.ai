import type { ComponentType } from 'react';
import type { RouteObject } from 'react-router';
import { RouteError } from '@/components/RouteError';
import { loadChunk } from '@/lib/staleChunk';
import { AdminShell } from './AdminShell';

const page =
  (load: () => Promise<{ default: ComponentType }>) =>
  async (): Promise<{ Component: ComponentType }> => ({
    Component: (await loadChunk(load)).default,
  });

/**
 * The back office web app (ADR 0045, ADR 0069): its own entry, built apart from the public app and served only on the
 * admin host. One guard (AdminLayout), a part per route. Kept separate from the router so tests can mount it.
 */
export const adminRoutes: RouteObject[] = [
  {
    path: 'admin',
    element: <AdminShell />,
    errorElement: <RouteError />,
    children: [
      {
        lazy: page(() => import('@/pages/admin/AdminLayout')),
        errorElement: <RouteError />,
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
    ],
  },
];
