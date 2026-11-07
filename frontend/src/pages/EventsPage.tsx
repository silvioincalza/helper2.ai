import { useState } from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { useEventsFeed, type EventRow } from '@/components/home/homeFeeds';
import { ListState, useEventText } from '@/components/home/listParts';
import { TermCommand } from '@/components/home/TermList';
import { StatusPill } from '@/components/ui/StatusPill';
import { useTitle } from '@/hooks/useTitle';
import '@/components/ui/term.css';
import './listing.css';

/** The Events page looks a year ahead; the API sends at most 60 events, soonest first. */
const EVENTS_PAGE_DAYS = 366;

type Kind = NonNullable<EventRow['kind']>;
type Format = NonNullable<EventRow['format']>;

/** The events grouped by the month they start in, in order. */
export function byMonth(rows: readonly EventRow[]): { month: string; rows: EventRow[] }[] {
  const groups: { month: string; rows: EventRow[] }[] = [];
  for (const row of rows) {
    const month = row.start.slice(0, 7);
    const last = groups[groups.length - 1];
    if (last && last.month === month) last.rows.push(row);
    else groups.push({ month, rows: [row] });
  }
  return groups;
}

/**
 * Events (v5): every event in the next year whose source states its dates, grouped by month. Filters by kind
 * (conference, hackathon…) and by format (online, in person), each only with the values the events have. A row opens
 * the resource page, as on Home. The "on now" and "in 2 days" pills count from the server's clock.
 */
export default function EventsPage() {
  const { t, i18n } = useTranslation();
  useTitle(t('eventsPage.title'));
  const feed = useEventsFeed(EVENTS_PAGE_DAYS);
  const text = useEventText();
  const [kind, setKind] = useState<Kind | null>(null);
  const [format, setFormat] = useState<Format | null>(null);
  const kinds = [...new Set(feed.rows.map((r) => r.kind).filter((k): k is Kind => k !== null))];
  const formats = [
    ...new Set(feed.rows.map((r) => r.format).filter((f): f is Format => f !== null)),
  ];
  const shown = feed.rows.filter(
    (r) => (kind === null || r.kind === kind) && (format === null || r.format === format),
  );
  const monthName = (month: string) =>
    new Intl.DateTimeFormat(i18n.language, {
      month: 'long',
      year: 'numeric',
      timeZone: 'UTC',
    }).format(new Date(`${month}-01T00:00:00Z`));
  const day = (iso: string) =>
    new Intl.DateTimeFormat(i18n.language, {
      day: 'numeric',
      weekday: 'short',
      timeZone: 'UTC',
    }).format(new Date(`${iso.slice(0, 10)}T00:00:00Z`));
  return (
    <div className="v4 v4-term h2-listing">
      <header className="v4-term__head">
        <div className="v4-term__bar">
          <h1>{t('eventsPage.title')}</h1>
          <TermCommand
            command="helper2 events --next 12m"
            total={feed.rows.length > 0 ? feed.total : null}
          />
        </div>
        <p className="h2-listing__lede">{t('eventsPage.lede')}</p>
      </header>
      {feed.rows.length > 0 && (kinds.length > 1 || formats.length > 1) && (
        <div className="h2-listing__filters">
          {kinds.length > 1 && (
            <div className="chips" role="group" aria-label={t('eventsPage.kind')}>
              <button
                type="button"
                className="chip"
                aria-pressed={kind === null}
                onClick={() => setKind(null)}
              >
                {t('eventsPage.allKinds')}
              </button>
              {kinds.map((k) => (
                <button
                  key={k}
                  type="button"
                  className="chip"
                  aria-pressed={kind === k}
                  onClick={() => setKind(kind === k ? null : k)}
                >
                  {t(`events.kind.${k}`)}
                </button>
              ))}
            </div>
          )}
          {formats.length > 1 && (
            <div className="chips" role="group" aria-label={t('eventsPage.format')}>
              {formats.map((f) => (
                <button
                  key={f}
                  type="button"
                  className="chip"
                  aria-pressed={format === f}
                  onClick={() => setFormat(format === f ? null : f)}
                >
                  {t(`events.format.${f}`)}
                </button>
              ))}
            </div>
          )}
        </div>
      )}
      <ListState
        feed={feed}
        level={2}
        errorText={t('events.error')}
        emptyText={t('eventsPage.empty')}
      />
      {feed.rows.length > 0 && shown.length === 0 && (
        <p className="h2-listing__none">{t('eventsPage.noneForFilter')}</p>
      )}
      {byMonth(shown).map((g) => (
        <section key={g.month} className="h2-listing__month" aria-labelledby={`m-${g.month}`}>
          <h2 id={`m-${g.month}`} className="h2-listing__month-h">
            {monthName(g.month)}
          </h2>
          <ul className="plain-list">
            {g.rows.map((r) => {
              const left = text.left(r, feed.now);
              return (
                <li key={r.id} className="v4-row h2-event">
                  <time className="h2-event__day" dateTime={r.start}>
                    {day(r.start)}
                  </time>
                  <div className="h2-event__main">
                    <p className="h2-event__meta">
                      {r.kind && <span className="h2-event__kind">{text.kind(r)}</span>}
                      {text.where(r) && <span>{text.where(r)}</span>}
                    </p>
                    <Link className="h2-event__title" to={r.href}>
                      {r.title}
                    </Link>
                  </div>
                  {r.left && (
                    <span className="h2-event__left">
                      <StatusPill tone={r.left.key === 'onNow' ? 'ok' : 'next'}>
                        {r.left.key === 'onNow' ? t('lists.onNow') : left}
                      </StatusPill>
                      {r.left.key === 'onNow' && left && (
                        <span className="h2-event__until">{left}</span>
                      )}
                    </span>
                  )}
                </li>
              );
            })}
          </ul>
        </section>
      ))}
    </div>
  );
}
