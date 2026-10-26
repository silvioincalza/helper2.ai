import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api } from '@/api/client';
import type { RecentResponse, ResultCard } from '@/api/types';
import { useApi } from '@/hooks/useApi';
import { formatDate } from '@/lib/format';
import { typeTagClass } from '@/lib/typeTag';
import { Carousel } from './Carousel';
import { Icon } from './Icon';

/**
 * Tools and research only: news and events come from a web-search API whose terms do not clearly allow
 * public redisplay (docs/sources.md), so the home page does not show them. Search still finds them.
 */
const ALL_TYPES: ResultCard['type'][] = ['MCP_SERVER', 'MODEL', 'LIBRARY', 'PAPER', 'AGENT', 'DATASET', 'PROMPT', 'SKILL', 'PRODUCT'];

/**
 * Data comes from GET /recent: Helper2's own index, newest by the date the SOURCE states, so the home page makes
 * no live source calls (GitHub and arXiv are rate-limited) and "new" really means newly published, not "matched a
 * keyword, then sorted". Everything from the last week, as many as the API gives (24), two rows in view.
 */
const SHOWN = 24;
const DAYS = 7;

/** The date a source stated (published, else updated); otherwise when Helper2.ai first saw it, labelled as such. */
function cardDate(card: ResultCard): { key: 'published' | 'updated' | 'firstSeen'; value: string; precision: 'INSTANT' | 'DAY' | 'UNKNOWN' } | null {
  const { published, updated, discovered } = card.timeline;
  if (published.status !== 'UNKNOWN' && published.value) return { key: 'published', value: published.value, precision: published.precision };
  if (updated.status !== 'UNKNOWN' && updated.value) return { key: 'updated', value: updated.value, precision: updated.precision };
  if (discovered.status === 'KNOWN' && discovered.value) return { key: 'firstSeen', value: discovered.value, precision: 'DAY' };
  return null;
}

/** What is new in the AI world this week. Shown on the home page while the box is empty. */
export function Radar() {
  const { t, i18n } = useTranslation();
  const params = new URLSearchParams({ days: String(DAYS), size: String(SHOWN) });
  for (const type of ALL_TYPES) params.append('type', type);
  const key = params.toString();
  const feed = useApi<RecentResponse>(`radar?${key}`, (signal) => api.listRecent(params, signal));
  const cards = (feed.data?.items ?? []).slice(0, SHOWN);

  return (
    <section aria-labelledby="radar-h" className="radar">
      <div className="radar-head">
        <h2 id="radar-h">{t('radar.title')}</h2>
      </div>
      <p className="muted small radar-lede">{t('radar.lede')}</p>

      <div aria-live="polite" aria-busy={feed.loading}>
        {feed.loading && (
          <div>
            <span className="sr-only">{t('common.loading')}</span>
            {[0, 1, 2].map((i) => <div key={i} className="skeleton-row" aria-hidden="true" />)}
          </div>
        )}
        {!feed.loading && feed.error !== undefined && (
          <div role="alert" className="state-box state-error">
            <p><Icon name="warn" /> {t('radar.error')}</p>
            <button type="button" className="btn" onClick={feed.reload}><Icon name="refresh" /> {t('common.retry')}</button>
          </div>
        )}
        {!feed.loading && feed.data && cards.length === 0 && <p className="state-box">{t('radar.empty')}</p>}
        {cards.length > 0 && (
          <Carousel items={cards} label={t('radar.title')} render={(card) => {
              const date = cardDate(card);
              const source = card.sources[0]?.sourceName;
              return (
                <li key={card.id} className="radar-row">
                  <div className="feed-meta">
                    <span className={typeTagClass(card.type)}>{t(`type.${card.type}`)}</span>
                    <span className={`radar-date${date?.key === 'firstSeen' ? ' is-first-seen' : ''}`}>
                      {date
                        ? <>{t(`radar.${date.key}`)} <time dateTime={date.value}>{formatDate(date.value, i18n.language, date.precision)}</time></>
                        : t('radar.noDate')}
                    </span>
                    {source && <span className="feed-source">{source}</span>}
                  </div>
                  <Link className="radar-title" to={`/resource/${encodeURIComponent(card.id)}`}>{card.title}</Link>
                  {card.description && <p className="radar-desc">{card.description}</p>}
                </li>
              );
            }} />
        )}
      </div>
    </section>
  );
}
