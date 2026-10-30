import { Navigate, useLocation, type RouteObject } from 'react-router';
import { Layout } from '@/components/Layout';
import { AppProviders } from '@/AppProviders';
import { askParams } from '@/lib/searchState';
import AdminPage from '@/pages/AdminPage';
import HomePage from '@/pages/HomePage';
import MissionPage from '@/pages/MissionPage';
import NotFoundPage from '@/pages/NotFoundPage';
import PrivacyPage from '@/pages/PrivacyPage';
import ResourcePage from '@/pages/ResourcePage';
import { SAVED_ENABLED } from '@/lib/features';
import SavedPage from '@/pages/SavedPage';
import SourcesPage from '@/pages/SourcesPage';

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
    children: [
      { index: true, element: <HomePage /> },
      { path: 'search', element: <SearchRedirect /> },
      { path: 'guide', element: <GuideRedirect /> },
      { path: 'resource/:id', element: <ResourcePage /> },
      { path: 'saved', element: SAVED_ENABLED ? <SavedPage /> : <Navigate replace to="/" /> },
      { path: 'sources', element: <SourcesPage /> },
      { path: 'admin', element: <AdminPage /> },
      { path: 'mission', element: <MissionPage /> },
      { path: 'privacy', element: <PrivacyPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
