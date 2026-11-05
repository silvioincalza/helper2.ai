import { Navigate, useLocation, useSearchParams } from 'react-router';
import Home from '@/pages/Home';
import { Results } from '@/components/results/Results';
import { Robots } from '@/components/Robots';
import { askedQuestion, parseSearchParams, showsHome } from '@/lib/searchState';

/**
 * The page at / and /search (ADR 0040). An empty / is only the home page; results live on /search. /?q=… still shows
 * them; an empty /search goes home. Search results are not indexed, but their links are followed (SEO review F8).
 */
export default function HomePage() {
  const [sp] = useSearchParams();
  const { pathname } = useLocation();
  if (showsHome(pathname, sp)) return <Home />;
  if (!parseSearchParams(sp).q && !askedQuestion(sp)) return <Navigate replace to="/" />;
  return (
    <>
      <Robots content="noindex, follow" />
      <Results />
    </>
  );
}
