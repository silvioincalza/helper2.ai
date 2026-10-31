import { useEffect, useRef, useState, type CSSProperties, type RefObject } from 'react';
import { Link, useSearchParams } from 'react-router';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { GuideAnswer } from '@/components/GuideAnswer';
import { GuideProgress } from '@/components/GuideProgress';
import { Icon } from '@/components/Icon';
import { OneBox } from '@/components/OneBox';
import { LogoMark } from '@/components/Logo';
import { RadarRings } from '@/components/RadarRings';
import { EventsBoard } from '@/components/NextEvents';
import { NewsBoard } from '@/components/Radar';
import { SearchResults } from '@/components/SearchResults';
import { useGuideStream } from '@/hooks/useGuideStream';
import { useTitle } from '@/hooks/useTitle';
import { askedQuestion, clampQuestion, homeParams, parseSearchParams, patchState, type SearchState } from '@/lib/searchState';

/** Example needs (`home.example.<id>`): a short label, and the full need it sends, which both searches AND asks the guide. */
const EXAMPLES = ['postgres', 'embeddings', 'translation'] as const;

/**
 * The home page (ADR 0040): with an empty box, the landing (hero, then News and Events). With a search or a question
 * in the URL, the results on the same page, under the box; they move to /search in the next design step.
 */
export default function HomePage() {
  const [sp] = useSearchParams();
  const empty = !parseSearchParams(sp).q && !askedQuestion(sp);
  return empty ? <Landing /> : <OnePage />;
}

/**
 * Home only: the radar hero with the mark, the title, the one box and three example needs; under it the News and
 * Events boards side by side (stacked below 1000px). An example both searches and asks the guide.
 */
function Landing() {
  const { t } = useTranslation();
  const [sp, setSp] = useSearchParams();
  useTitle('');
  const ask = (need: string) => {
    const q = clampQuestion(need);
    if (q) setSp(homeParams(patchState(parseSearchParams(sp), { q }), q, sp));
  };
  return (
    <div className="landing">
      <section className="home-hero" aria-labelledby="home-h">
        <RadarRings />
        <div className="home-hero-inner">
          <Link to="/" className="hero-logo" aria-label={t('nav.home')}>
            <LogoMark />
            <span className="hero-word">helper2.ai</span>
          </Link>
          <p className="eyebrow">{t('home.eyebrow')}</p>
          <h1 id="home-h" className="hero-title">
            {t('home.title')} <span className="title-accent">{t('home.titleAccent')}</span>
          </h1>
          <OneBox hero />
          <section aria-labelledby="examples-h" className="examples">
            <h2 id="examples-h" className="sr-only">{t('home.examples')}</h2>
            <ul className="plain-list hero-chips">
              {EXAMPLES.map((id) => {
                const need = t(`home.example.${id}.need`);
                return (
                  <li key={id}>
                    <button type="button" className="hero-chip" title={need} onClick={() => ask(need)}>{t(`home.example.${id}.label`)}</button>
                  </li>
                );
              })}
            </ul>
          </section>
        </div>
      </section>
      <div className="home-boards">
        <NewsBoard />
        <EventsBoard />
      </div>
    </div>
  );
}

/** A search or a question: the box, the guide's answer, then the results. */
function OnePage() {
  const { t } = useTranslation();
  const [sp, setSp] = useSearchParams();
  const state = parseSearchParams(sp);
  const asked = askedQuestion(sp);
  useTitle(state.q);

  // A filter, sort or page change keeps the reader where they are; only a new page starts at the top.
  const go = (next: SearchState, ask: string) => setSp(homeParams(next, ask, sp), { preventScrollReset: true });
  const update = (next: SearchState) => go(next, asked);
  const ask = (question: string) => {
    const q = clampQuestion(question);
    if (q) go(patchState(state, { q }), q);
  };
  const words = state.q.split(/\s+/).filter(Boolean).length;
  const slot = useRef<HTMLDivElement>(null);
  const pin = usePinnedBox(slot);

  return (
    <div className="one-page">
      <section className="home-hero is-compact" aria-labelledby="home-h">
        <div className="home-hero-inner">
          <h1 id="home-h" className="sr-only">{t('home.title')} {t('home.titleAccent')}</h1>
          <div ref={slot} className={`hero-box-slot${pin ? ' is-pinned' : ''}`}
            style={pin ? { height: pin.height, '--pin-top': `${pin.top}px` } as CSSProperties : undefined}>
            <div className="hero-box-pin">
              <OneBox />
            </div>
          </div>
        </div>
      </section>

      {asked && <GuideSection onClose={() => go(state, '')} />}

      {!asked && words >= 4 && (
        <button type="button" className="btn suggest-guide" onClick={() => ask(state.q)}>
          {t('home.suggestGuide', { q: state.q })}
        </button>
      )}

      <SearchResults state={state} update={update} />
    </div>
  );
}

/**
 * Where to pin the hero's box once it has scrolled under the header (null while it is in view): the header's bottom
 * edge (0 on a phone, where the header scrolls away) and the box's height, which its slot keeps so nothing jumps.
 * The same element moves, so typing keeps its focus.
 */
function usePinnedBox(slot: RefObject<HTMLDivElement | null>): { top: number; height: number } | null {
  const [pin, setPin] = useState<{ top: number; height: number } | null>(null);
  useEffect(() => {
    const el = slot.current;
    if (!el) return;
    let frame = 0;
    const check = () => {
      frame = 0;
      const header = document.querySelector('.site-header');
      const top = header && getComputedStyle(header).position === 'sticky' ? header.getBoundingClientRect().bottom : 0;
      const box = el.getBoundingClientRect();
      setPin((p) => {
        if (box.top >= top) return null;
        return p && p.top === top ? p : { top, height: p?.height ?? box.height };
      });
    };
    const onScroll = () => { if (!frame) frame = requestAnimationFrame(check); };
    check();
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll);
    return () => {
      window.removeEventListener('scroll', onScroll);
      window.removeEventListener('resize', onScroll);
      cancelAnimationFrame(frame);
    };
  }, [slot]);
  return pin;
}

/** What went wrong, in words: the server's explanation, else why the stream failed, else a generic line. */
function guideError(err: unknown, t: TFunction): string {
  if (!(err instanceof ApiError)) return t('guide.error');
  if (err.stream) return t(`guide.streamFailure.${err.stream}`);
  return err.problem?.detail ?? t('guide.error');
}

/**
 * The guide's answer to the question in the URL, with each real step from the event stream while it works.
 * Closing the panel or asking something else unmounts or restarts the stream (see useGuideStream).
 */
function GuideSection({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const { question, id, steps, answer, error, busy, refine, retry } = useGuideStream();

  return (
    <section aria-labelledby="guide-h" className="guide-panel">
      <div className="guide-panel-head">
        <p className="eyebrow">{t('guide.title')}</p>
        <button type="button" className="btn btn-icon" onClick={onClose} aria-label={t('guide.close')}>×</button>
      </div>
      <h2 id="guide-h" className="guide-question"><q>{question}</q></h2>
      {/* The status sits outside the busy region: screen readers hold a busy region's announcements back. */}
      {busy && <GuideProgress key={id} steps={steps} />}
      <div aria-live="polite" aria-busy={busy}>
        {error !== null && (
          <div className="state-box state-error">
            <p role="alert"><Icon name="warn" /> {guideError(error, t)}</p>
            <button type="button" className="btn btn-primary" onClick={retry}><Icon name="refresh" /> {t('common.retry')}</button>
          </div>
        )}
        {answer && <GuideAnswer answer={answer} onRefine={refine} />}
      </div>
    </section>
  );
}
