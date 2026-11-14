import { Link, useSearchParams } from 'react-router';
import { useFromHere } from '@/features/search/fromResults';
import { useTranslation } from 'react-i18next';
import { useEventsFeed, type EventRow } from '@/features/home/homeFeeds';
import { ListState, useEventText } from '@/features/home/listParts';
import { TermCommand } from '@/features/home/TermList';
import { OriginLabel } from '@/ui/Badges';
import { StatusPill } from '@/ui/StatusPill';
import { aiMark } from '@/lib/aiMark';
import { formatMonth, weekDay } from '@/lib/format';
import { useTitle } from '@/hooks/useTitle';
import '@/ui/term.css';
import '../../ui/listing.css';

const EVENTS_PAGE_DAYS = 366;

type Kind = NonNullable<EventRow['kind']>;
type Format = NonNullable<EventRow['format']>;

function countBy<V extends string>(
  rows: readonly EventRow[],
  of: (r: EventRow) => V | null,
): [V, number][] {
  const count = new Map<V, number>();
  for (const r of rows) {
    const v = of(r);
    if (v !== null) count.set(v, (count.get(v) ?? 0) + 1);
  }
  return [...count.entries()];
}

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

export default function EventsPage() {
  const { t } = useTranslation();
  const from = useFromHere();
  useTitle(t('eventsPage.title'));
  const feed = useEventsFeed(EVENTS_PAGE_DAYS);
  const text = useEventText();
  const [params, setParams] = useSearchParams();
  const kind = (params.get('kind') || null) as Kind | null;
  const format = (params.get('format') || null) as Format | null;
  const setFilter = (key: 'kind' | 'format', value: string | null) =>
    setParams(
      (p) => {
        const next = new URLSearchParams(p);
        if (value) next.set(key, value);
        else next.delete(key);
        return next;
      },
      { replace: true, preventScrollReset: true },
    );
  const setKind = (k: Kind | null) => setFilter('kind', k);
  const setFormat = (f: Format | null) => setFilter('format', f);
  const withChosen = <V extends string>(counts: [V, number][], chosen: V | null): [V, number][] =>
    chosen && !counts.some(([v]) => v === chosen) ? [...counts, [chosen, 0]] : counts;
  const kinds = withChosen(
    countBy(feed.rows, (r) => r.kind),
    kind,
  );
  const formats = withChosen(
    countBy(feed.rows, (r) => r.format),
    format,
  );
  const n = (count: number) => <span className="h2-chip__n">{count}</span>;
  const shown = feed.rows.filter(
    (r) => (kind === null || r.kind === kind) && (format === null || r.format === format),
  );
  return (
    <div className="h2-app h2-term h2-listing">
      <header className="h2-term__head">
        <div className="h2-term__bar">
          <h1>{t('eventsPage.title')}</h1>
          <TermCommand
            command="helper2 events --next 12m"
            total={feed.rows.length > 0 ? feed.total : null}
          />
        </div>
        <p className="h2-listing__lede">{t('eventsPage.lede')}</p>
      </header>
      {feed.rows.length > 0 && (kinds.length > 1 || formats.length > 1 || kind || format) && (
        <div className="h2-listing__filters">
          {(kinds.length > 1 || kind) && (
            <div className="chips" role="group" aria-label={t('eventsPage.kind')}>
              <button
                type="button"
                className="chip"
                aria-pressed={kind === null}
                onClick={() => setKind(null)}
              >
                {t('eventsPage.allKinds')} {n(feed.rows.length)}
              </button>
              {kinds.map(([k, count]) => (
                <button
                  key={k}
                  type="button"
                  className="chip"
                  aria-pressed={kind === k}
                  onClick={() => setKind(kind === k ? null : k)}
                >
                  {t(`events.kind.${k}`, { defaultValue: k })} {n(count)}
                </button>
              ))}
            </div>
          )}
          {(formats.length > 1 || format) && (
            <div className="chips" role="group" aria-label={t('eventsPage.format')}>
              {formats.map(([f, count]) => (
                <button
                  key={f}
                  type="button"
                  className="chip"
                  aria-pressed={format === f}
                  onClick={() => setFormat(format === f ? null : f)}
                >
                  {t(`events.format.${f}`, { defaultValue: f })} {n(count)}
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
          <div className="h2-listing__month-head">
            <h2 id={`m-${g.month}`} className="h2-listing__month-h">
              {formatMonth(g.month)}
            </h2>
            <p className="h2-listing__month-n">
              {t('eventsPage.inMonth', { count: g.rows.length })}
            </p>
          </div>
          <ul className="plain-list">
            {g.rows.map((r) => {
              const left = text.left(r, feed.now);
              return (
                <li key={r.id} className="h2-term-row h2-event">
                  <time
                    className="h2-event__day"
                    dateTime={r.start}
                    {...(r.aiRead ? aiMark() : {})}
                  >
                    {weekDay(r.start)}
                  </time>
                  <div className="h2-event__main">
                    <p className="h2-event__meta">
                      {r.kind && (
                        <span className="h2-event__kind" {...(r.aiRead ? aiMark() : {})}>
                          {text.kind(r)}
                        </span>
                      )}
                      {text.where(r) && (
                        <span {...(r.aiRead ? aiMark() : {})}>{text.where(r)}</span>
                      )}
                      {r.aiRead && <OriginLabel origin="AI_EXTRACTED" />}
                    </p>
                    <Link className="h2-event__title" to={r.href} state={from}>
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
