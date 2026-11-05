import { useEffect } from 'react';
import { useLocation } from 'react-router';
import { useTranslation } from 'react-i18next';
import { EventsBoard } from '@/components/home/EventsBoard';
import { HomeExamples } from '@/components/home/HomeExamples';
import { NewsBoard } from '@/components/home/NewsBoard';
import { OneBox } from '@/components/OneBox';
import { api } from '@/api/client';
import { useApi } from '@/hooks/useApi';
import { useTitle } from '@/hooks/useTitle';
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
  const { hash, key } = useLocation();
  const sources = useApi('sources', (signal) => api.listSources(signal));
  useTitle('');
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
        <HomeExamples />
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
