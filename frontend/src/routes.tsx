import type { ComponentType } from 'react';
import type { RouteObject } from 'react-router';
import { Layout } from '@/shell/Layout';
import { AppProviders } from '@/AppProviders';
import { RouteError } from '@/shell/RouteError';
import { loadChunk } from '@/lib/staleChunk';
import HomeOrResultsPage from '@/features/search/HomeOrResultsPage';
import ResourcePage from '@/features/resource/ResourcePage';

const page =
  (load: () => Promise<{ default: ComponentType }>) =>
  async (): Promise<{ Component: ComponentType }> => ({
    Component: (await loadChunk(load)).default,
  });

const textPage = (load: () => Promise<{ default: ComponentType }>) =>
  page(async () => {
    const [mod] = await Promise.all([
      load(),
      import('@/hooks/usePolicy').then((p) => p.preloadPolicy()),
    ]);
    return mod;
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
          { path: 'resource/:id/:slug?', element: <ResourcePage /> },
          { path: 'saved', lazy: page(() => import('@/features/saved/SavedPage')) },
          { path: 'news', lazy: page(() => import('@/features/news/NewsPage')) },
          { path: 'events', lazy: page(() => import('@/features/events/EventsPage')) },
          { path: 'sources', lazy: page(() => import('@/features/sources/SourcesPage')) },
          { path: 'account', lazy: page(() => import('@/features/account/AccountPage')) },
          { path: 'signin', lazy: page(() => import('@/features/account/SignInPage')) },
          { path: 'mission', lazy: textPage(() => import('@/features/pages/MissionPage')) },
          { path: '*', lazy: page(() => import('@/shell/NotFoundPage')) },
        ],
      },
    ],
  },
];
