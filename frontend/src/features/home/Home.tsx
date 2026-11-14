import { upTo, upToHeight } from '@/lib/breakpoints';
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
import { HomeExamples } from '@/features/home/HomeExamples';
import { HomeLists } from '@/features/home/HomeLists';
import { RadarLegend } from '@/features/home/RadarLegend';
import { OneBox } from '@/features/search/OneBox';
import { api } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { useStickyEdge } from '@/hooks/useStickyEdge';
import { useTitle } from '@/hooks/useTitle';
import { radarSources } from '@/lib/sourceCounts';
import '@/features/home/home.css';

const HomeRadar = lazy(() =>
  import('@/features/home/HomeRadar').then((m) => ({ default: m.HomeRadar })),
);

const DOES = ['search', 'recommend', 'proof'] as const;
const FOLD_AFTER_PX = 24;
const PHONE = upTo('small');
const SHORT = upToHeight('short');

function useScrollState(
  bar: RefObject<HTMLDivElement | null>,
  more: RefObject<HTMLDivElement | null>,
  sticks: boolean,
) {
  const [folded, setFolded] = useState(false);
  const [stuck, setStuck] = useState(false);
  const releaseBelow = useRef<number | null>(null);
  useEffect(() => {
    const onScroll = () => {
      const y = window.scrollY;
      const phone = typeof window.matchMedia === 'function' && window.matchMedia(PHONE).matches;
      setFolded((was) => sticks && phone && (was ? y > 0 : y > FOLD_AFTER_PX));
      const el = bar.current;
      if (!el) return;
      const top = parseFloat(getComputedStyle(el).top) || 0;
      const atEdge = el.getBoundingClientRect().top <= top + 0.5;
      const release = releaseBelow.current;
      if (!sticks || y <= 0) releaseBelow.current = null;
      else if (atEdge) releaseBelow.current = release ?? y - heightOf(more.current);
      else if (release !== null && y < release) releaseBelow.current = null;
      setStuck(releaseBelow.current !== null);
    };
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll);
    document.addEventListener('transitionend', onScroll);
    return () => {
      window.removeEventListener('scroll', onScroll);
      window.removeEventListener('resize', onScroll);
      document.removeEventListener('transitionend', onScroll);
    };
  }, [bar, more, sticks]);
  return { folded, stuck: stuck || folded };
}

function heightOf(el: HTMLElement | null): number {
  return el?.getBoundingClientRect().height ?? 0;
}

function useFocusKeptInView(home: RefObject<HTMLElement | null>) {
  useEffect(() => {
    const el = home.current;
    if (!el) return undefined;
    const afterFold = (e: TransitionEvent) => {
      const focused = document.activeElement;
      if (e.propertyName !== 'max-height' || !(focused instanceof HTMLElement)) return;
      const { top, bottom } = focused.getBoundingClientRect();
      const onScreen = bottom > 0 && top < window.innerHeight;
      if (el.contains(focused) && onScreen) focused.scrollIntoView({ block: 'nearest' });
    };
    el.addEventListener('transitionend', afterFold);
    return () => el.removeEventListener('transitionend', afterFold);
  }, [home]);
}

export default function Home() {
  const { t } = useTranslation();
  const { hash, key } = useLocation();
  const sources = useApi('sources', (signal) => api.listSources(signal));
  useTitle('');
  const external = radarSources(sources.data ?? []);
  const home = useRef<HTMLDivElement>(null);
  const bar = useRef<HTMLDivElement>(null);
  const more = useRef<HTMLDivElement>(null);
  const [dots, setDots] = useState<HTMLDivElement | null>(null);
  const titleBlock = useRef<HTMLDivElement>(null);
  const sticks = !useMediaQuery(SHORT, false);
  const { folded, stuck } = useScrollState(bar, more, sticks);
  const barHeight = useHeight(bar);
  useStickyEdge('top', bar, { sticks, foldsAway: stuck ? null : more });
  useFocusKeptInView(home);

  useEffect(() => {
    const id = hash.slice(1);
    if (id) document.getElementById(id)?.scrollIntoView({ block: 'start' });
  }, [hash, key]);

  return (
    <div
      ref={home}
      className="h2-app h2-home"
      style={{ '--pinned-h': `${barHeight}px` } as CSSProperties}
    >
      <section className={`h2-hero${folded ? ' is-folded' : ''}`} aria-labelledby="home-h">
        <Suspense fallback={null}>
          <HomeRadar sources={external} title={titleBlock} dots={dots} />
        </Suspense>
        <div ref={titleBlock} className="h2-wrap h2-hero__text">
          <h1 id="home-h" className="h2-hero__title">
            <span className="h2-hero__name">{t('home.name')}</span>{' '}
            <span className="h2-hero__what">{t('home.title')}</span>
          </h1>
          <p className="h2-hero__lede">{t('home.lede')}</p>
        </div>
      </section>

      <div ref={bar} className={`h2-pinned${stuck ? ' is-stuck' : ''}`}>
        <div className="h2-wrap h2-pinned__inner">
          <OneBox hero />
          <div ref={more} className="h2-pinned__more">
            <p className="h2-hint">
              <span className="h2-hint__keys">{t('home.hint')}</span>
              <span className="h2-hint__touch">{t('home.hintTouch')}</span>
            </p>
            <HomeExamples />
            {external.length > 0 && <RadarLegend sources={external} />}
          </div>
        </div>
      </div>
      <div ref={setDots} className="h2-radar-dots" />

      <div className="h2-wrap">
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
