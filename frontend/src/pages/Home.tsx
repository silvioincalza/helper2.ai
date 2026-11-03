import { useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { useTranslation } from 'react-i18next';
import { EventsBoard } from '@/components/home/EventsBoard';
import { NewsBoard } from '@/components/home/NewsBoard';
import { OneBox } from '@/components/OneBox';
import { useSources } from '@/context/SourcesContext';
import { useTitle } from '@/hooks/useTitle';
import { askParams, clampQuestion, RESULTS_PATH } from '@/lib/searchState';
import '@/components/home/home.css';

/** Example needs (`home.example.<id>`): a short label, and the full need it sends, which both searches AND asks the guide. */
const EXAMPLES = ['emails', 'pdf', 'laptop', 'postgres'] as const;
/** What helper2 does, in three steps (`home.does.<id>`). */
const DOES = ['search', 'recommend', 'proof'] as const;

/**
 * Home page (ADR 0040, 2): one headline that says what the site is for, the box, example needs for every kind of
 * user, what helper2 does in three steps, and the News and Events lists. The box and the examples open the results
 * on /search. The header's News and Events links land on their lists (#news, #events).
 */
export default function Home() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { hash } = useLocation();
  const sources = useSources();
  useTitle('');
  const ask = (need: string) => {
    const q = clampQuestion(need);
    if (q) void navigate({ pathname: RESULTS_PATH, search: askParams(q).toString() });
  };
  const count = (sources.data ?? []).filter((s) => s.enabled).length;

  // A header link to #news or #events: the lists sit below the fold, and the router does not scroll to a hash.
  useEffect(() => {
    const id = hash.slice(1);
    if (id) document.getElementById(id)?.scrollIntoView({ block: 'start' });
  }, [hash]);

  return (
    <div className="one-page home">
      <section className="home-hero" aria-labelledby="home-h">
        <h1 id="home-h" className="home-title">{t('home.title')}</h1>
        <p className="home-lede">{count > 0 ? t('home.lede', { count }) : t('home.ledeNoCount')}</p>
        <OneBox hero />
        <section aria-labelledby="examples-h" className="home-examples">
          <h2 id="examples-h" className="home-examples__label">{t('home.examples')}</h2>
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
      </section>

      <section className="home-does" aria-labelledby="does-h">
        <h2 id="does-h" className="home-does__title">{t('home.does.title')}</h2>
        <ol className="plain-list home-does__list">
          {DOES.map((id, i) => (
            <li key={id} className="home-does__item">
              <span className="home-does__step" aria-hidden="true">{i + 1}</span>
              <h3>{t(`home.does.${id}.title`)}</h3>
              <p>{t(`home.does.${id}.text`)}</p>
            </li>
          ))}
        </ol>
      </section>

      <div className="h2-boards">
        <NewsBoard />
        <EventsBoard />
      </div>
    </div>
  );
}
