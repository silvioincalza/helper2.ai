import { Link, useSearchParams } from 'react-router';
import { useFromHere } from '@/features/search/fromResults';
import { useTranslation } from 'react-i18next';
import { useAiNewsFeed, type NewsRow } from '@/features/home/homeFeeds';
import { ListState } from '@/features/home/listParts';
import { TermCommand } from '@/features/home/TermList';
import { useTitle } from '@/hooks/useTitle';
import { longLocalDay, shortDay } from '@/lib/format';
import '@/ui/term.css';
import '../../ui/listing.css';

export function topHosts(rows: readonly NewsRow[], max: number): string[] {
  const count = new Map<string, number>();
  for (const r of rows) if (r.host) count.set(r.host, (count.get(r.host) ?? 0) + 1);
  return [...count.entries()]
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .slice(0, max)
    .map(([host]) => host);
}

const LEAD = 1;
const SECOND = 6;
const LATEST = 5;
const HOST_CHIPS = 6;

export default function NewsPage() {
  const { t } = useTranslation();
  const from = useFromHere();
  useTitle(t('newsPage.title'));
  const feed = useAiNewsFeed();
  const [params, setParams] = useSearchParams();
  const host = params.get('site') || null;
  const setHost = (next: string | null) =>
    setParams(next ? { site: next } : {}, { replace: true, preventScrollReset: true });
  const top = topHosts(feed.rows, HOST_CHIPS);
  const hosts = host && !top.includes(host) ? [...top, host] : top;
  const rows = host ? feed.rows.filter((r) => r.host === host) : feed.rows;
  const lead = rows.slice(0, LEAD);
  const second = rows.slice(LEAD, LEAD + SECOND);
  const latest = rows.slice(LEAD + SECOND, LEAD + SECOND + LATEST);
  const earlier = rows.slice(LEAD + SECOND + LATEST);
  const today = longLocalDay(feed.now);
  const byline = (r: NewsRow) => (
    <p className="h2-story__by">
      {r.host}
      {r.date && (
        <>
          {' · '}
          <time dateTime={r.date}>{shortDay(r.date, feed.now)}</time>
        </>
      )}
    </p>
  );
  const story = (r: NewsRow, size: 'lead' | 'second' | 'small') => (
    <article key={r.id} className={`h2-story is-${size}`}>
      {size === 'small' ? (
        <h3 className="h2-story__h">
          <Link to={r.href} state={from}>
            {r.title}
          </Link>
        </h3>
      ) : (
        <h2 className="h2-story__h">
          <Link to={r.href} state={from}>
            {r.title}
          </Link>
        </h2>
      )}
      {byline(r)}
    </article>
  );
  return (
    <div className="h2-app h2-term h2-listing h2-news">
      <header className="h2-term__head h2-news__masthead">
        {today && <p className="h2-news__date">{today}</p>}
        <div className="h2-term__bar">
          <h1>{t('newsPage.title')}</h1>
          <TermCommand
            command="helper2 news --7d"
            total={feed.rows.length > 0 ? feed.total : null}
          />
        </div>
        <p className="h2-listing__lede">{t('newsPage.lede')}</p>
      </header>
      {hosts.length > 1 && (
        <div className="chips h2-listing__filters" role="group" aria-label={t('newsPage.bySite')}>
          <button
            type="button"
            className="chip"
            aria-pressed={host === null}
            onClick={() => setHost(null)}
          >
            {t('newsPage.allSites')}
          </button>
          {hosts.map((h) => (
            <button
              key={h}
              type="button"
              className="chip"
              aria-pressed={host === h}
              onClick={() => setHost(host === h ? null : h)}
            >
              {h}
            </button>
          ))}
        </div>
      )}
      <ListState
        feed={feed}
        level={2}
        errorText={t('newsPage.error')}
        emptyText={t('newsPage.empty')}
      />
      {feed.rows.length > 0 && rows.length === 0 && (
        <p className="h2-listing__none">{t('newsPage.noneForSite')}</p>
      )}
      {rows.length > 0 && (
        <>
          <div className={latest.length > 0 ? 'h2-news__front' : 'h2-news__front is-alone'}>
            <section className="h2-news__top" aria-label={t('newsPage.top')}>
              {lead.map((r) => story(r, 'lead'))}
              {second.length > 0 && (
                <div className="h2-news__second">{second.map((r) => story(r, 'second'))}</div>
              )}
            </section>
            {latest.length > 0 && (
              <section className="h2-news__latest" aria-labelledby="news-latest">
                <h2 id="news-latest" className="h2-news__section-h">
                  {t('newsPage.latest')}
                </h2>
                {latest.map((r) => story(r, 'small'))}
              </section>
            )}
          </div>
          {earlier.length > 0 && (
            <section className="h2-news__earlier" aria-labelledby="news-earlier">
              <h2 id="news-earlier" className="h2-news__section-h">
                {t('newsPage.earlier')}
              </h2>
              <div className="h2-news__grid">{earlier.map((r) => story(r, 'small'))}</div>
            </section>
          )}
        </>
      )}
    </div>
  );
}
