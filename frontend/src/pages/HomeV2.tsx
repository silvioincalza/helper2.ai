import { useNavigate } from 'react-router';
import { useTranslation } from 'react-i18next';
import { EventsBoard } from '@/components/home/EventsBoard';
import { NewsBoard } from '@/components/home/NewsBoard';
import { OneBox } from '@/components/OneBox';
import { RadarRings } from '@/components/RadarRings';
import { useTitle } from '@/hooks/useTitle';
import { askParams, clampQuestion, RESULTS_PATH } from '@/lib/searchState';

/** Example needs (`home.example.<id>`): a short label, and the full need it sends, which both searches AND asks the guide. */
const EXAMPLES = ['postgres', 'embeddings', 'translation'] as const;

/**
 * Design v2 home page (ADR 0040, 2): only the home page. The radar hero, the one box and example needs; under them
 * the News and Events boards. The box and the examples open the results on /search.
 */
export default function HomeV2() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  useTitle('');
  const ask = (need: string) => {
    const q = clampQuestion(need);
    if (q) void navigate({ pathname: RESULTS_PATH, search: askParams(q).toString() });
  };

  return (
    <div className="one-page home-v2">
      <section className="home-hero" aria-labelledby="home-h">
        <RadarRings />
        <div className="home-hero-inner">
          <p className="eyebrow">{t('home.eyebrow')}</p>
          <h1 id="home-h">{t('home.title')} <span className="title-accent">{t('home.titleAccent')}</span></h1>
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
      </section>
      <div className="h2-boards">
        <NewsBoard />
        <EventsBoard />
      </div>
    </div>
  );
}
