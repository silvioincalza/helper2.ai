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
        lazy: page(() => import('./AdminLayout')),
        errorElement: <RouteError />,
        children: [
          { index: true, lazy: page(() => import('./AdminOverview')) },
          { path: 'review', lazy: page(() => import('./ReviewQueuePage')) },
          {
            path: 'review/research/:runId',
            lazy: page(() => import('./ResearchTracePage')),
          },
          { path: 'runs', lazy: page(() => import('./ScoutRunsPage')) },
          { path: 'enrichment', lazy: page(() => import('./EnrichmentQueuePage')) },
          { path: 'examples', lazy: page(() => import('./ExamplesPage')) },
          { path: 'audit', lazy: page(() => import('./AuditPage')) },
          { path: 'settings', lazy: page(() => import('./SettingsPage')) },
        ],
      },
    ],
  },
];
