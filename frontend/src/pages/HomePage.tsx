import { useEffect, useRef, useState, type CSSProperties, type RefObject } from 'react';
import { useSearchParams } from 'react-router';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/api/client';
import { GuideAnswer } from '@/components/GuideAnswer';
import { GuideProgress } from '@/components/GuideProgress';
import { Icon } from '@/components/Icon';
import { OneBox } from '@/components/OneBox';
import { RadarRings } from '@/components/RadarRings';
import { SourceStrip } from '@/components/SourceStrip';
import { NextEvents } from '@/components/NextEvents';
import { Radar } from '@/components/Radar';
import { SearchResults } from '@/components/SearchResults';
import { useGuideStream } from '@/hooks/useGuideStream';
import { useTitle } from '@/hooks/useTitle';
import { askedQuestion, clampQuestion, homeParams, parseSearchParams, patchState, type SearchState } from '@/lib/searchState';

/** Example needs (`home.example.<id>`): a short label, and the full need it sends, which both searches AND asks the guide. */
const EXAMPLES = ['postgres', 'embeddings', 'translation'] as const;

/**
 * One page under the header's one box: the guide's answer, then search results; with an empty box,
 * the hero, example needs and a short list of what is new; how the guide works lives on /mission. Everything lives in the URL (see OneBox).
 */
export default function HomePage() {
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
  const empty = !state.q && !asked;
  const slot = useRef<HTMLDivElement>(null);
  const pin = usePinnedBox(slot);
  const hero = useRef<HTMLElement>(null);
  useHeroFade(hero);

  return (
    <div className="one-page">
      {/* The box keeps its place in the tree whether the page is empty or not, so typing never loses focus. */}
      <section ref={hero} className={`home-hero${empty ? '' : ' is-compact'}`} aria-labelledby="home-h">
        {empty && <RadarRings />}
        <div className="home-hero-inner">
          <h1 id="home-h" className={empty ? undefined : 'sr-only'}>
            {t('home.title')} <span className="title-accent">{t('home.titleAccent')}</span>
          </h1>
          {empty && <p className="lede">{t('home.lede')}</p>}
          <div ref={slot} className={`hero-box-slot${pin ? ' is-pinned' : ''}`}
            style={pin ? { height: pin.height, '--pin-top': `${pin.top}px` } as CSSProperties : undefined}>
            <div className="hero-box-pin">
              {/* The tagline above the box, the hint and the examples under it pin with it, so they are always in view. */}
              {(empty || pin) && <p className="eyebrow">{t('home.eyebrow')}</p>}
              <OneBox hero />
              {empty && <p className="muted small box-hint">{t('home.hint')}</p>}
              {empty && (
                <section aria-labelledby="examples-h" className="examples">
                  <h2 id="examples-h" className="sr-only">{t('home.examples')}</h2>
                  <ul className="plain-list inline-list">
                    {EXAMPLES.map((id) => {
                      const need = t(`home.example.${id}.need`);
                      return (
                        <li key={id}>
                          <button type="button" className="chip chip-ghost" title={need} onClick={() => ask(need)}>{t(`home.example.${id}.label`)}</button>
                        </li>
                      );
                    })}
                  </ul>
                </section>
              )}
            </div>
          </div>
          {empty && <SourceStrip />}
        </div>
      </section>

      {asked && <GuideSection onClose={() => go(state, '')} />}

      {!asked && words >= 4 && (
        <button type="button" className="btn suggest-guide" onClick={() => ask(state.q)}>
          {t('home.suggestGuide', { q: state.q })}
        </button>
      )}

      <SearchResults state={state} update={update} />

      {/* What is new and what comes next, side by side on a wide screen, stacked on a narrow one. */}
      {empty && (
        <div className="home-feeds">
          <Radar />
          <NextEvents />
        </div>
      )}
    </div>
  );
}

/** Scrolled this far, the hero's title and lede are gone. */
const HERO_FADE_PX = 220;

/**
 * Sets `--hero-out` on the hero, 0 at the top to 1 once scrolled HERO_FADE_PX: the CSS fades the title and lede by it.
 * `is-scrolled` hides the sources line under the box as soon as the page moves.
 * Done here rather than with a CSS scroll timeline, which Firefox and older Safari do not run.
 */
function useHeroFade(hero: RefObject<HTMLElement | null>) {
  useEffect(() => {
    const el = hero.current;
    if (!el) return;
    let frame = 0;
    const update = () => {
      frame = 0;
      const out = Math.min(1, Math.max(0, window.scrollY / HERO_FADE_PX));
      el.style.setProperty('--hero-out', String(out));
      el.classList.toggle('is-out', out >= 1);
      el.classList.toggle('is-scrolled', window.scrollY > 8);
    };
    const onScroll = () => { if (!frame) frame = requestAnimationFrame(update); };
    update();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => {
      window.removeEventListener('scroll', onScroll);
      cancelAnimationFrame(frame);
    };
  }, [hero]);
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
