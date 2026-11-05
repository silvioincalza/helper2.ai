import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { useTranslation } from 'react-i18next';
import { EventsBoard } from '@/components/home/EventsBoard';
import { NewsBoard } from '@/components/home/NewsBoard';
import { OneBox } from '@/components/OneBox';
import { useSources } from '@/context/SourcesContext';
import { useTitle } from '@/hooks/useTitle';
import { pickExamples, type Example } from '@/lib/examples';
import {
  clampQuery,
  parseSearchParams,
  patchState,
  RESULTS_PATH,
  toSearchParams,
} from '@/lib/searchState';
import '@/components/home/home.css';

/** What helper2 does, in three steps (`home.does.<id>`). */
const DOES = ['search', 'recommend', 'proof'] as const;

/**
 * Home page (ADR 0040, 2): what you see first is the search and what is new: one headline, the box, example needs,
 * then "New this week" and "Coming up" right under them. What helper2 does follows as a quiet strip. The box and the examples open the results
 * on /search. The header's News and Events links land on their lists (#news, #events).
 */
export default function Home() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { hash, key } = useLocation();
  const sources = useSources();
  useTitle('');
  // A new pick per page view, the same one while the page stays.
  const [examples] = useState(() => pickExamples());
  // An example searches with its path; asking the guide stays a choice on the results (it spends a daily answer).
  const search = (example: Example) => {
    const q = clampQuery(t(`home.example.${example.id}.query`));
    const state = patchState(parseSearchParams(new URLSearchParams()), { q, path: example.path });
    if (q) void navigate({ pathname: RESULTS_PATH, search: toSearchParams(state).toString() });
  };
  const count = (sources.data ?? []).filter((s) => s.enabled).length;

  // A header link to #news or #events: the lists sit below the fold, and the router does not scroll to a hash. On the
  // key too, so a second click on the same link scrolls again. The list's scroll-margin keeps it below the sticky header.
  useEffect(() => {
    const id = hash.slice(1);
    if (id) document.getElementById(id)?.scrollIntoView({ block: 'start' });
  }, [hash, key]);

  return (
    <div className="one-page home">
      <section className="home-hero" aria-labelledby="home-h">
        <h1 id="home-h" className="home-title">
          {t('home.title')}
        </h1>
        <p className="home-lede">{count > 0 ? t('home.lede', { count }) : t('home.ledeNoCount')}</p>
        <OneBox hero />
        <section aria-labelledby="examples-h" className="home-examples">
          <h2 id="examples-h" className="home-examples__label">
            {t('home.examples')}
          </h2>
          <ul className="plain-list inline-list">
            {examples.map((example) => (
              <li key={example.id}>
                <button type="button" className="chip chip-ghost" onClick={() => search(example)}>
                  {t(`home.example.${example.id}.label`)}
                </button>
              </li>
            ))}
          </ul>
        </section>
      </section>

      <div className="h2-boards">
        <NewsBoard />
        <EventsBoard />
      </div>

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
  );
}
