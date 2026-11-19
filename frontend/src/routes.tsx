import type { ComponentType } from 'react';
import type { RouteObject } from 'react-router';
import { Layout } from '@/shell/Layout';
import { AppProviders } from './AppProviders';
import { RouteError } from '@/shell/RouteError';
import { loadChunk } from '@/lib/staleChunk';
import HomeOrResultsPage from '@/shell/HomeOrResultsPage';
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

const hubPage = page(() => import('@/features/hubs/HubPage'));

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
          { path: 'news', lazy: page(() => import('@/features/feeds/NewsPage')) },
          { path: 'events', lazy: page(() => import('@/features/feeds/EventsPage')) },
          { path: 'sources', lazy: page(() => import('@/features/sources/SourcesPage')) },
          { path: 'account', lazy: page(() => import('@/features/account/AccountPage')) },
          { path: 'signin', lazy: page(() => import('@/features/account/SignInPage')) },
          { path: 'mission', lazy: textPage(() => import('@/features/pages/MissionPage')) },
          { path: 'privacy', lazy: textPage(() => import('@/features/pages/PrivacyPage')) },
          { path: 'terms', lazy: textPage(() => import('@/features/pages/TermsPage')) },
          { path: 'support', lazy: page(() => import('@/features/monetisation/SupportPage')) },
          {
            path: 'support/thanks',
            lazy: page(() => import('@/features/monetisation/SupportThanksPage')),
          },
          { path: 'ai-apps', lazy: hubPage },
          { path: 'ai-models', lazy: hubPage },
          { path: 'ai-agents', lazy: hubPage },
          { path: 'mcp-servers', lazy: hubPage },
          { path: 'libraries', lazy: hubPage },
          { path: 'agent-skills', lazy: hubPage },
          { path: 'datasets', lazy: hubPage },
          { path: 'papers', lazy: hubPage },
          { path: 'prompts', lazy: hubPage },
          { path: 'courses', lazy: hubPage },
          { path: '*', lazy: page(() => import('@/shell/NotFoundPage')) },
        ],
      },
    ],
  },
];
