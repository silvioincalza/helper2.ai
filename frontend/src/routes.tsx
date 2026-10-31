import { Navigate, useSearchParams, type RouteObject } from 'react-router';
import { Layout } from '@/components/Layout';
import { AppProviders } from '@/AppProviders';
import { hasResults } from '@/lib/searchState';
import AccountPage from '@/pages/AccountPage';
import AdminPage from '@/pages/AdminPage';
import HomePage from '@/pages/HomePage';
import MissionPage from '@/pages/MissionPage';
import NotFoundPage from '@/pages/NotFoundPage';
import PrivacyPage from '@/pages/PrivacyPage';
import ResultsPage from '@/pages/ResultsPage';
import ResourcePage from '@/pages/ResourcePage';
import SavedPage from '@/pages/SavedPage';
import SourcesPage from '@/pages/SourcesPage';
import TermsPage from '@/pages/TermsPage';

/** `/` is the home page; with a search or a question it shows the results page, which names /search canonical. */
function HomeOrResults() {
  const [sp] = useSearchParams();
  return hasResults(sp) ? <ResultsPage /> : <HomePage />;
}

/** /search with nothing to search is the home page. */
function SearchRoute() {
  const [sp] = useSearchParams();
  return hasResults(sp) ? <ResultsPage /> : <Navigate replace to="/" />;
}

/** Kept separate from the router so tests can mount it in createMemoryRouter. */
export const routes: RouteObject[] = [
  {
    element: <AppProviders><Layout /></AppProviders>,
    children: [
      { index: true, element: <HomeOrResults /> },
      { path: 'search', element: <SearchRoute /> },
      { path: 'resource/:id', element: <ResourcePage /> },
      { path: 'saved', element: <SavedPage /> },
      { path: 'sources', element: <SourcesPage /> },
      { path: 'account', element: <AccountPage /> },
      { path: 'admin', element: <AdminPage /> },
      { path: 'mission', element: <MissionPage /> },
      { path: 'privacy', element: <PrivacyPage /> },
      { path: 'terms', element: <TermsPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
