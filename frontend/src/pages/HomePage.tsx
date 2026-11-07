import { lazy, Suspense } from 'react';
import { Navigate, useLocation, useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import Home from '@/pages/Home';
import { OneBox } from '@/components/OneBox';
import { ResultsSkeleton } from '@/components/results/ResultsSkeleton';
import { Robots } from '@/components/Robots';
import { useTitle } from '@/hooks/useTitle';
import { askedQuestion, parseSearchParams, showsHome } from '@/lib/searchState';

/**
 * The results view loads on demand, outside the first download (ADR 0028 (part was 0042), decision 7): most visits start on Home. The page's
 * h1 and the search box are not part of it, so they are there at once and never mount twice; the skeleton holds the
 * list's place while the chunk loads.
 */
const Results = lazy(() =>
  import('@/components/results/Results').then((m) => ({ default: m.Results })),
);

/**
 * The page at / and /search (ADR 0028). An empty / is only the home page; results live on /search. /?q=… still shows
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
      <ResultsPage />
    </>
  );
}

/** The results page: its h1 and the box, then the results view. */
function ResultsPage() {
  const { t } = useTranslation();
  const [sp] = useSearchParams();
  const q = parseSearchParams(sp).q;
  useTitle(q);
  return (
    <div className="v4 h2-results">
      <h1 className="sr-only">{t('results.heading', { q })}</h1>
      <div className="h2-results__box">
        <OneBox />
      </div>
      <Suspense fallback={<ResultsSkeleton />}>
        <Results />
      </Suspense>
    </div>
  );
}
