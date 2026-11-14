import type { ComponentType } from 'react';
import type { RouteObject } from 'react-router';
import { RouteError } from '@/shell/RouteError';
import { loadChunk } from '@/lib/staleChunk';
import { AdminShell } from './AdminShell';

const page =
  (load: () => Promise<{ default: ComponentType }>) =>
  async (): Promise<{ Component: ComponentType }> => ({
    Component: (await loadChunk(load)).default,
  });

export const adminRoutes: RouteObject[] = [
  {
    path: 'admin',
    element: <AdminShell />,
    errorElement: <RouteError />,
    children: [
      {
        lazy: page(() => import('@/admin/AdminLayout')),
        errorElement: <RouteError />,
        children: [
          { index: true, lazy: page(() => import('@/admin/AdminOverview')) },
          { path: 'review', lazy: page(() => import('@/admin/ReviewQueuePage')) },
          { path: 'runs', lazy: page(() => import('@/admin/ScoutRunsPage')) },
          { path: 'enrichment', lazy: page(() => import('@/admin/EnrichmentQueuePage')) },
          { path: 'examples', lazy: page(() => import('@/admin/ExamplesPage')) },
          { path: 'audit', lazy: page(() => import('@/admin/AuditPage')) },
          { path: 'settings', lazy: page(() => import('@/admin/SettingsPage')) },
        ],
      },
    ],
  },
];
