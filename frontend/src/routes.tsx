import type { ComponentType } from 'react';
import { Navigate, useLocation, type RouteObject } from 'react-router';
import { Layout } from '@/components/Layout';
import { AppProviders } from '@/AppProviders';
import { askParams } from '@/lib/searchState';
import { SAVED_ENABLED } from '@/lib/features';
import HomePage from '@/pages/HomePage';

/** A page in its own chunk, loaded on the first visit to its path: the home page's bundle carries none of them. */
const page = (load: () => Promise<{ default: ComponentType }>): NonNullable<RouteObject['lazy']> =>
  async () => ({ Component: (await load()).default });

/** Old /search links keep working: same parameters, one page. */
function SearchRedirect() {
  const { search } = useLocation();
  return <Navigate replace to={{ pathname: '/', search }} />;
}

/** Old /guide?q=… links keep working: the question goes to the guide and to search on the one page. */
function GuideRedirect() {
  const q = new URLSearchParams(useLocation().search).get('q') ?? '';
  return <Navigate replace to={{ pathname: '/', search: askParams(q).toString() }} />;
}

/** Kept separate from the router so tests can mount it in createMemoryRouter. */
export const routes: RouteObject[] = [
  {
    element: <AppProviders><Layout /></AppProviders>,
    // A first visit straight to a lazy page renders nothing until its chunk (a few kB) arrives.
    HydrateFallback: () => null,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'search', element: <SearchRedirect /> },
      { path: 'guide', element: <GuideRedirect /> },
      { path: 'resource/:id', lazy: page(() => import('@/pages/ResourcePage')) },
      SAVED_ENABLED
        ? { path: 'saved', lazy: page(() => import('@/pages/SavedPage')) }
        : { path: 'saved', element: <Navigate replace to="/" /> },
      { path: 'sources', lazy: page(() => import('@/pages/SourcesPage')) },
      { path: 'account', lazy: page(() => import('@/pages/AccountPage')) },
      { path: 'admin', lazy: page(() => import('@/pages/AdminPage')) },
      { path: 'mission', lazy: page(() => import('@/pages/MissionPage')) },
      { path: 'privacy', lazy: page(() => import('@/pages/PrivacyPage')) },
      { path: 'terms', lazy: page(() => import('@/pages/TermsPage')) },
      { path: '*', lazy: page(() => import('@/pages/NotFoundPage')) },
    ],
  },
];
