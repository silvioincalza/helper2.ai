import { lazy, Suspense, useEffect, useRef, useState, type RefObject } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { useTranslation } from 'react-i18next';
import { HomeLists } from '@/components/home/HomeLists';
import { RadarLegend } from '@/components/home/RadarLegend';
import { OneBox } from '@/components/OneBox';
import { api } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useTitle } from '@/hooks/useTitle';
import { pickExamples, type Example } from '@/lib/examples';
import {
  clampQuery,
  parseSearchParams,
  patchState,
  QUESTION_WORDS,
  RESULTS_PATH,
  toSearchParams,
} from '@/lib/searchState';
import '@/components/home/home.css';

/** The radar is decoration behind the title (absolutely placed, so nothing shifts when it arrives): it loads after the
 * page, outside the first download. */
const HomeRadar = lazy(() =>
  import('@/components/home/HomeRadar').then((m) => ({ default: m.HomeRadar })),
);

/** What helper2 does, in three steps (`home.does.<id>`). */
const DOES = ['search', 'recommend', 'proof'] as const;
/** On a phone the title folds away after this much scroll, and opens again back at the top. */
const FOLD_AFTER_PX = 24;
const PHONE = '(max-width: 720px)';

/**
 * While the page scrolls: on a phone the title folds once the page has moved FOLD_AFTER_PX, and opens again only at
 * the very top (so the fold cannot bounce); the search box is "stuck" once it reaches the header, and then drops its
 * hints. One passive scroll listener; nothing moves on its own.
 */
function useScrollState(bar: RefObject<HTMLDivElement | null>) {
  const [folded, setFolded] = useState(false);
  const [stuck, setStuck] = useState(false);
  useEffect(() => {
    const onScroll = () => {
      const y = window.scrollY;
      const phone = typeof window.matchMedia === 'function' && window.matchMedia(PHONE).matches;
      setFolded((was) => phone && (was ? y > 0 : y > FOLD_AFTER_PX));
      const el = bar.current;
      if (!el) return;
      const top = parseFloat(getComputedStyle(el).top) || 0;
      setStuck(y > 0 && el.getBoundingClientRect().top <= top + 0.5);
    };
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll);
    // The fold moves the box up to the header without a scroll: measure again once it has.
    document.addEventListener('transitionend', onScroll);
    return () => {
      window.removeEventListener('scroll', onScroll);
      window.removeEventListener('resize', onScroll);
      document.removeEventListener('transitionend', onScroll);
    };
  }, [bar]);
  // A folded title leaves the box right under the header: stuck, also before the fold has been measured.
  return { folded, stuck: stuck || folded };
}

/**
 * Home page (ADR 0042, design v4): what the site is, the radar of its sources behind the title, the search box pinned
 * under the header, then New this week and Coming up. What helper2 does follows as a quiet strip. The box and the
 * examples open the results on /search. The header's links land on the lists (#news, #events).
 */
export default function Home() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { hash, key } = useLocation();
  // One GET /sources for the count and the radar (the cache shares it with the Sources page).
  const sources = useApi('sources', (signal) => api.listSources(signal));
  useTitle('');
  // A new pick per page view, the same one while the page stays.
  const [examples] = useState(() => pickExamples());
  // An example searches with its path; asking the guide stays a choice on the results (it spends a daily answer).
  const search = (example: Example) => {
    const q = clampQuery(t(`home.example.${example.id}.query`));
    const state = patchState(parseSearchParams(new URLSearchParams()), { q, path: example.path });
    if (q) void navigate({ pathname: RESULTS_PATH, search: toSearchParams(state).toString() });
  };
  const enabled = (sources.data ?? []).filter((s) => s.enabled);
  const bar = useRef<HTMLDivElement>(null);
  const { folded, stuck } = useScrollState(bar);

  // A header link to #news or #events: the lists sit below the fold, and the router does not scroll to a hash. On the
  // key too, so a second click on the same link scrolls again. scroll-margin keeps the list below the pinned box.
  useEffect(() => {
    const id = hash.slice(1);
    if (id) document.getElementById(id)?.scrollIntoView({ block: 'start' });
  }, [hash, key]);

  return (
    <div className="v4 v4-home">
      <section className={`v4-hero${folded ? ' is-folded' : ''}`} aria-labelledby="home-h">
        <Suspense fallback={null}>
          <HomeRadar sources={enabled} />
        </Suspense>
        <div className="v4-wrap v4-hero__text">
          {enabled.length > 0 && (
            <p className="v4-eyebrow">{t('home.eyebrow', { count: enabled.length })}</p>
          )}
          <h1 id="home-h" className="v4-hero__title">
            {t('home.title')}
          </h1>
          <p className="v4-hero__lede">{t('home.lede')}</p>
        </div>
      </section>

      <div ref={bar} className={`v4-pinned${stuck ? ' is-stuck' : ''}`}>
        <div className="v4-wrap v4-pinned__inner">
          <OneBox hero />
          <div className="v4-pinned__more">
            <p className="v4-hint">{t('home.hint', { count: QUESTION_WORDS })}</p>
            <section aria-labelledby="examples-h" className="home-examples">
              <h2 id="examples-h" className="home-examples__label">
                {t('home.examples')}
              </h2>
              <ul className="plain-list inline-list">
                {examples.map((example) => (
                  <li key={example.id}>
                    <button
                      type="button"
                      className="chip chip-ghost"
                      onClick={() => search(example)}
                    >
                      {t(`home.example.${example.id}.label`)}
                    </button>
                  </li>
                ))}
              </ul>
            </section>
            {enabled.length > 0 && <RadarLegend sources={enabled} />}
          </div>
        </div>
      </div>

      <div className="v4-wrap">
        <HomeLists initial={hash === '#events' ? 'events' : 'news'} />

        <section className="home-does" aria-labelledby="does-h">
          <h2 id="does-h" className="home-does__title">
            {t('home.does.title')}
          </h2>
          <ol className="plain-list home-does__list">
            {DOES.map((id, i) => (
              <li key={id} className="home-does__item">
                <span className="home-does__step" aria-hidden="true">
                  {i + 1}
                </span>
                <h3>{t(`home.does.${id}.title`)}</h3>
                <p>{t(`home.does.${id}.text`)}</p>
              </li>
            ))}
          </ol>
        </section>
      </div>
    </div>
  );
}
