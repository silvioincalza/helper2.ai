import {
  lazy,
  Suspense,
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type RefObject,
} from 'react';
import { useLocation } from 'react-router';
import { useTranslation } from 'react-i18next';
import { HomeExamples } from '@/components/home/HomeExamples';
import { HomeLists } from '@/components/home/HomeLists';
import { RadarLegend } from '@/components/home/RadarLegend';
import { OneBox } from '@/components/OneBox';
import { api } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useTitle } from '@/hooks/useTitle';
import { radarSources } from '@/lib/v4Fields';
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
 * Home page (ADR 0042, design v4): what the site is, the radar of its sources around the title, the search box pinned
 * under the header, then New this week and Coming up. What helper2 does follows as a quiet strip. The box and the
 * examples open the results on /search. The header's links land on the lists (#news, #events).
 */
export default function Home() {
  const { t } = useTranslation();
  const { hash, key } = useLocation();
  // One GET /sources for the radar and its legend (the cache shares it with the Sources page).
  const sources = useApi('sources', (signal) => api.listSources(signal));
  useTitle('');
  // The radar and its legend show the same sources: the enabled ones run by someone else.
  const external = radarSources(sources.data ?? []);
  const bar = useRef<HTMLDivElement>(null);
  const titleBlock = useRef<HTMLDivElement>(null);
  const { folded, stuck } = useScrollState(bar);
  const barHeight = useHeight(bar);

  // A header link to #news or #events: the lists sit below the fold, and the router does not scroll to a hash. On the
  // key too, so a second click on the same link scrolls again. scroll-margin keeps the list below the pinned box.
  useEffect(() => {
    const id = hash.slice(1);
    if (id) document.getElementById(id)?.scrollIntoView({ block: 'start' });
  }, [hash, key]);

  return (
    <div className="v4 v4-home" style={{ '--pinned-h': `${barHeight}px` } as CSSProperties}>
      <section className={`v4-hero${folded ? ' is-folded' : ''}`} aria-labelledby="home-h">
        <Suspense fallback={null}>
          <HomeRadar sources={external} box={bar} title={titleBlock} />
        </Suspense>
        <div ref={titleBlock} className="v4-wrap v4-hero__text">
          <h1 id="home-h" className="v4-hero__title">
            {/* One h1 on two lines: the site's name, then what it is. The space keeps the two apart when read. */}
            <span className="v4-hero__name">{t('home.name')}</span>{' '}
            <span className="v4-hero__what">{t('home.title')}</span>
          </h1>
          <p className="v4-hero__lede">{t('home.lede')}</p>
        </div>
      </section>

      <div ref={bar} className={`v4-pinned${stuck ? ' is-stuck' : ''}`}>
        <div className="v4-wrap v4-pinned__inner">
          <OneBox hero />
          <div className="v4-pinned__more">
            <p className="v4-hint">{t('home.hint')}</p>
            <HomeExamples />
            {external.length > 0 && <RadarLegend sources={external} />}
          </div>
        </div>
      </div>

      <div className="v4-wrap">
        <HomeLists />

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

/**
 * The pinned box's height, so the heads of New this week and Coming up stick just under it while their table
 * scrolls. 0 where ResizeObserver is missing (tests): the heads then stick under the header.
 */
function useHeight(ref: RefObject<HTMLElement | null>): number {
  const [height, setHeight] = useState(0);
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(() =>
      setHeight(Math.round(el.getBoundingClientRect().height)),
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, [ref]);
  return height;
}
