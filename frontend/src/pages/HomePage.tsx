import { useEffect, useRef, useState, type CSSProperties, type RefObject } from 'react';
import { useNavigate } from 'react-router';
import { useTranslation } from 'react-i18next';
import { OneBox } from '@/components/OneBox';
import { HomeBoards } from '@/components/HomeBoards';
import { RadarRings } from '@/components/RadarRings';
import { useTitle } from '@/hooks/useTitle';
import { askParams, RESULTS_PATH } from '@/lib/searchState';

/** Example needs (`home.example.<id>`): a short label, and the full need it sends, which both searches AND asks the guide. */
const EXAMPLES = ['postgres', 'embeddings', 'translation'] as const;

/**
 * The home page (ADR 0040, 2): the radar hero with the one box and example needs, then the News and Events boards.
 * The box and the examples open the results page; how the guide works lives on /mission.
 */
export default function HomePage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  useTitle('');
  const ask = (need: string) => void navigate({ pathname: RESULTS_PATH, search: askParams(need).toString() });
  const slot = useRef<HTMLDivElement>(null);
  const pin = usePinnedBox(slot);
  const hero = useRef<HTMLElement>(null);
  useHeroFade(hero);

  return (
    <div className="home-page">
      <section ref={hero} className="home-hero" aria-labelledby="home-h">
        <RadarRings />
        <div className="home-hero-inner">
          <h1 id="home-h">
            {t('home.title')} <span className="title-accent">{t('home.titleAccent')}</span>
          </h1>
          <p className="lede">{t('home.lede')}</p>
          <div ref={slot} className={`hero-box-slot${pin ? ' is-pinned' : ''}`}
            style={pin ? { height: pin.height, '--pin-top': `${pin.top}px` } as CSSProperties : undefined}>
            <div className="hero-box-pin">
              {/* The tagline above the box, the hint and the examples under it pin with it, so they are always in view. */}
              <p className="eyebrow">{t('home.eyebrow')}</p>
              <OneBox hero />
              <p className="muted small box-hint">{t('home.hint')}</p>
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
            </div>
          </div>
        </div>
      </section>

      <HomeBoards />
    </div>
  );
}

/** Scrolled this far, the hero's title and lede are gone. */
const HERO_FADE_PX = 220;

/**
 * Sets `--hero-out` on the hero, 0 at the top to 1 once scrolled HERO_FADE_PX: the CSS fades the title and lede by it.
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
