import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api } from '@/api/client';
import type { AdminOverview as Overview } from '@/api/types';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { StatusPill } from '@/components/ui/StatusPill';
import { useApi } from '@/hooks/useApi';
import { useRetry } from '@/hooks/useRetry';
import { formatDate } from '@/lib/format';
import { RUN_TONE } from './adminModel';

const SHOWN_RUNS = 5;

/**
 * The landing page (UJ-9, ADR 0045): what needs a person now. Every number links to the view that acts on it. Counts
 * only; trends live in Grafana. The server computes the overview at most once a minute, so Refresh is cheap.
 */
export default function AdminOverview() {
  const { t } = useTranslation();
  const overview = useApi('admin/overview', (s) => api.getAdminOverview(s), { cache: false });
  const { retry, focusRef: heading } = useRetry(overview.reload, overview.loading);
  const data = overview.data ?? overview.previous;

  return (
    <section aria-labelledby="ov-h" className="h2-admin__part">
      <h2 id="ov-h" ref={heading} tabIndex={-1} className="h2-admin__h">{t('admin.overview.title')}</h2>
      {overview.loading && !data ? <p role="status" className="muted">{t('common.loading')}</p> : overview.error !== undefined && !data ? (
        <StatePanel tone="error" level={3} title={t('admin.overview.loadError')} next={<RetryButton onClick={retry} />} />
      ) : data ? <OverviewParts data={data} onRefresh={overview.reload} refreshing={overview.loading} /> : null}
    </section>
  );
}

function OverviewParts({ data, onRefresh, refreshing }: { data: Overview; onRefresh: () => void; refreshing: boolean }) {
  const { t, i18n } = useTranslation();
  const open = data.openReviewItems.reduce((sum, k) => sum + k.count, 0);
  return (
    <>
      <div className="h2-admin__tools">
        <p className="muted">{t('admin.overview.updated', { at: formatDate(data.generatedAt, i18n.language, 'INSTANT') })}</p>
        <button type="button" className="btn" aria-disabled={refreshing || undefined} onClick={() => { if (!refreshing) onRefresh(); }}>{t('admin.overview.refresh')}</button>
        {data.grafanaUrl && <a href={data.grafanaUrl} target="_blank" rel="noopener noreferrer">{t('admin.overview.trends')}</a>}
      </div>

      <h3 className="h2-admin__h3">{t('admin.overview.open', { count: open })}</h3>
      <ul className="h2-admin__counts">
        {data.openReviewItems.map(({ kind, count }) => (
          <li key={kind}>
            <Link to={`/admin/review?kind=${kind}`}><span className="h2-admin__count">{count}</span> {t(`admin.kind.${kind}`)}</Link>
          </li>
        ))}
      </ul>

      <h3 className="h2-admin__h3">{t('admin.overview.scout')}</h3>
      <ul className="h2-admin__counts">
        <li><span><span className="h2-admin__count">{data.enrichmentBacklog}</span> {t('admin.overview.backlog')}</span></li>
        <li>
          <Link to="/admin/runs"><span className="h2-admin__count">{data.failedRunsLast24h}</span> {t('admin.overview.failed')}</Link>
        </li>
      </ul>

      <div className="table-wrap">
        <table aria-labelledby="ov-budgets">
          <caption id="ov-budgets" className="h2-admin__caption">{t('admin.overview.budgets')}</caption>
          <thead>
            <tr>
              <th scope="col">{t('admin.overview.col.budget')}</th>
              <th scope="col">{t('admin.overview.col.used')}</th>
            </tr>
          </thead>
          <tbody>
            {data.budgets.map((b) => (
              <tr key={b.name}>
                <th scope="row">{t(`admin.budget.${b.name}`, { defaultValue: b.name })}</th>
                <td><Usage used={b.usedToday ?? null} limit={b.dailyLimit} /></td>
              </tr>
            ))}
            <tr>
              <th scope="row">{t('admin.overview.webCredits')}</th>
              <td>{data.webCredits ? <Usage used={data.webCredits.usedToday} limit={data.webCredits.dailyCap ?? null} /> : t('admin.overview.notCounted')}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <h3 className="h2-admin__h3">{t('admin.overview.lastRuns')}</h3>
      {data.recentRuns.length === 0 ? <p className="muted">{t('admin.noRuns')}</p> : (
        <ul className="h2-admin__runs">
          {data.recentRuns.slice(0, SHOWN_RUNS).map((r) => (
            <li key={r.id}>
              <StatusPill tone={RUN_TONE[r.status]}>{t(`admin.runStatus.${r.status}`)}</StatusPill>{' '}
              {r.sourceId} · {formatDate(r.startedAt, i18n.language, 'INSTANT')}
            </li>
          ))}
        </ul>
      )}
      <p><Link to="/admin/runs">{t('admin.overview.allRuns')}</Link></p>
    </>
  );
}

/** "120 of 500" with a meter; the words carry the number, the bar only repeats it. 0 is off; no limit is said so. */
function Usage({ used, limit }: { used: number | null; limit: number | null }) {
  const { t } = useTranslation();
  if (limit === 0) return <>{t('admin.overview.off')}</>;
  if (used === null) return <>{t('admin.overview.unknown')}</>;
  if (limit === null) return <>{t('admin.overview.noCap', { used })}</>;
  return (
    <span className="h2-admin__usage">
      {t('admin.overview.usedOf', { used, limit })}
      <meter min={0} max={limit} value={Math.min(used, limit)} high={limit * 0.8} aria-hidden="true" />
    </span>
  );
}
