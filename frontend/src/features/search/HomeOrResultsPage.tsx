import { lazy, Suspense } from 'react';
import { Navigate, useLocation, useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import Home from '@/features/home/Home';
import { OneBox } from '@/features/search/OneBox';
import { ResultsSkeleton } from '@/features/search/ResultsSkeleton';
import { Robots } from '@/shell/Robots';
import { useTitle } from '@/hooks/useTitle';
import { askedQuestion, parseSearchParams, showsHome } from '@/lib/searchState';

const Results = lazy(() =>
  import('@/features/search/Results').then((m) => ({ default: m.Results })),
);

export default function HomeOrResultsPage() {
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

function ResultsPage() {
  const { t } = useTranslation();
  const [sp] = useSearchParams();
  const q = parseSearchParams(sp).q;
  useTitle(q);
  return (
    <div className="h2-app h2-results">
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
