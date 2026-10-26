import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api } from '@/api/client';
import type { ResultCard, UpcomingEventsResponse } from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { Carousel } from './Carousel';
import { Icon } from './Icon';

/** As many as the API gives (60, enough for three months); only those starting in the next three months are listed, two rows in view. */
const SHOWN = 60;
const WINDOW_DAYS = 90;

/** Starts within WINDOW_DAYS of `now` (the server's `generatedAt`, so the window follows the server's clock). */
function startsSoon(card: ResultCard, now: number): boolean {
  const start = Date.parse(card.timeline.eventStart?.value ?? '');
  return Number.isNaN(now) || Number.isNaN(start) || start <= now + WINDOW_DAYS * 86_400_000;
}

/** "6–12 Dec 2026" from the days the source states; dates are calendar days, so format them in UTC. */
export function eventDays(card: ResultCard, lang: string): string | null {
  const start = card.timeline.eventStart;
  if (!start || start.status === 'UNKNOWN' || !start.value) return null;
  const fmt = new Intl.DateTimeFormat(lang, { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
  const end = card.timeline.eventEnd;
  const from = new Date(start.value);
  if (end && end.status !== 'UNKNOWN' && end.value && end.value !== start.value) return fmt.formatRange(from, new Date(end.value));
  return fmt.format(from);
}

/**
 * The next AI events: conferences (huggingface/ai-deadlines) and industry events, soonest first. Only events
 * whose source states when they take place are listed (GET /events/upcoming); nothing is called live.
 * Web-search (Tavily) events never state dates, so they never appear here (see the note in Radar).
 */
export function NextEvents() {
  const { t, i18n } = useTranslation();
  const feed = useApi<UpcomingEventsResponse>('next-events', (signal) =>
    api.listUpcomingEvents(new URLSearchParams({ size: String(SHOWN) }), signal));
  const now = Date.parse(feed.data?.generatedAt ?? '');
  const cards = (feed.data?.items ?? []).filter((card) => startsSoon(card, now));

  return (
    <section aria-labelledby="events-h" className="radar next-events">
      <div className="radar-head">
        <h2 id="events-h">{t('events.title')}</h2>
      </div>
      <p className="muted small radar-lede">{t('events.lede')}</p>
      <div aria-live="polite" aria-busy={feed.loading}>
        {feed.loading && (
          <div>
            <span className="sr-only">{t('common.loading')}</span>
            {[0, 1, 2].map((i) => <div key={i} className="skeleton-row" aria-hidden="true" />)}
          </div>
        )}
        {!feed.loading && feed.error !== undefined && (
          <div role="alert" className="state-box state-error">
            <p><Icon name="warn" /> {t('events.error')}</p>
            <button type="button" className="btn" onClick={feed.reload}><Icon name="refresh" /> {t('common.retry')}</button>
          </div>
        )}
        {!feed.loading && feed.data && cards.length === 0 && <p className="state-box">{t('events.empty')}</p>}
        {cards.length > 0 && (
          <Carousel items={cards} label={t('events.title')} render={(card) => (
              <li key={card.id} className="radar-row event-row">
                <div className="feed-meta">
                  {(card.eventKind || card.eventFormat) && (
                    <span className="type-tag event-kind">
                      {[card.eventKind && t(`events.kind.${card.eventKind}`), card.eventFormat && t(`events.format.${card.eventFormat}`)]
                        .filter(Boolean).join(' · ')}
                    </span>
                  )}
                  <time className="event-days" dateTime={card.timeline.eventStart?.value ?? undefined}>{eventDays(card, i18n.language)}</time>
                  {card.sources[0] && <span className="feed-source">{card.sources[0].sourceName}</span>}
                </div>
                <Link className="radar-title" to={`/resource/${encodeURIComponent(card.id)}`}>{card.title}</Link>
                {card.location && <p className="event-place">{card.location}</p>}
                {card.description && <p className="radar-desc">{card.description}</p>}
              </li>
            )} />
        )}
      </div>
    </section>
  );
}
