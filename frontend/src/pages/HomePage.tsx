import { Navigate, useLocation, useSearchParams } from 'react-router';
import Home from '@/pages/Home';
import { Results } from '@/components/results/Results';
import { askedQuestion, parseSearchParams, showsHome } from '@/lib/searchState';

/**
 * The page at / and /search (ADR 0040). An empty / is only the home page; results live on /search. /?q=… still shows
 * them and names /search as the canonical page; an empty /search goes home.
 */
export default function HomePage() {
  const [sp] = useSearchParams();
  const { pathname } = useLocation();
  if (showsHome(pathname, sp)) return <Home />;
  if (!parseSearchParams(sp).q && !askedQuestion(sp)) return <Navigate replace to="/" />;
  return (
    <>
      {pathname === '/' && <link rel="canonical" href={`/search?${sp.toString()}`} />}
      <Results />
    </>
  );
}
