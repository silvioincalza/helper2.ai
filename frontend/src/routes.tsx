import type { ComponentType } from 'react';
import type { RouteObject } from 'react-router';
import { Layout } from '@/shell/Layout';
import { AppProviders } from '@/AppProviders';
import { RouteError } from '@/shell/RouteError';
import { loadChunk } from '@/lib/staleChunk';
import HomeOrResultsPage from '@/features/search/HomeOrResultsPage';

const page =
  (load: () => Promise<{ default: ComponentType }>) =>
  async (): Promise<{ Component: ComponentType }> => ({
    Component: (await loadChunk(load)).default,
  });

export const routes: RouteObject[] = [
  {
    element: (
      <AppProviders>
        <Layout />
      </AppProviders>
    ),
    errorElement: <RouteError />,
    children: [
      {
        errorElement: <RouteError />,
        children: [
          { index: true, element: <HomeOrResultsPage /> },
          { path: 'search', element: <HomeOrResultsPage /> },
          { path: 'saved', lazy: page(() => import('@/features/saved/SavedPage')) },
          { path: 'account', lazy: page(() => import('@/features/account/AccountPage')) },
          { path: 'signin', lazy: page(() => import('@/features/account/SignInPage')) },
        ],
      },
    ],
  },
];
