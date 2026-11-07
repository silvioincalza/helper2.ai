import './adminText';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import type { AdminOverview as Overview, AudiencePath, ExampleCheckOutcome } from '@/api/types';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { StatusPill } from '@/components/ui/StatusPill';
import { Icon } from '@/components/Icon';
import { useApi } from '@/hooks/useApi';
import { useRetry } from '@/hooks/useRetry';
import { formatDate, safeHref } from '@/lib/format';
import { ScrollBox } from '@/components/ui/ScrollBox';
import { AdminEmpty, AdminHead, AdminLoading } from './AdminHead';
import { PATHS, RUN_TONE, SHOWN_PER_PATH } from './adminModel';

const SHOWN_RUNS = 5;
const COMMAND = 'helper2 status';
/** The checks that hide an active example, in the order the page lists them. */
const HIDING: readonly ExampleCheckOutcome[] = ['WEAK', 'FAIL', 'PENDING'];

/**
 * The landing page (UJ-9, ADR 0026): what needs a person now. Every number links to the view that acts on it. Counts
 * only; trends live in Grafana. The server computes the overview at most once a minute, so Refresh is cheap.
 */
export default function AdminOverview() {
  const { t } = useTranslation();
  const overview = useApi('admin/overview', (s) => adminApi.getAdminOverview(s), { cache: false });
  const { retry, focusRef: heading } = useRetry(overview.reload, overview.loading);
  const data = overview.data ?? overview.previous;

  return (
    <section aria-labelledby="ov-h" className="h2-admin__part">
      <AdminHead
        id="ov-h"
        title={t('admin.overview.title')}
        command={COMMAND}
        headingRef={heading}
      />
      {overview.loading && !data ? (
        <AdminLoading command={COMMAND} />
      ) : overview.error !== undefined && !data ? (
        <StatePanel
          tone="error"
          level={3}
          title={t('admin.overview.loadError')}
          next={<RetryButton onClick={retry} />}
        />
      ) : data ? (
        <>
          {overview.error !== undefined && !overview.loading && (
            <p role="alert" className="banner banner-warn">
              <Icon name="warn" /> {t('admin.overview.refreshError')}
            </p>
          )}
          <OverviewParts data={data} onRefresh={overview.reload} refreshing={overview.loading} />
        </>
      ) : null}
    </section>
  );
}

function OverviewParts({
  data,
  onRefresh,
  refreshing,
}: {
  data: Overview;
  onRefresh: () => void;
  refreshing: boolean;
}) {
  const { t, i18n } = useTranslation();
  const open = data.openReviewItems.reduce((sum, k) => sum + k.count, 0);
  // From server config, but only an http(s) link is ever rendered, like every other link built from data.
  const grafana = data.grafanaUrl ? safeHref(data.grafanaUrl) : undefined;
  return (
    <>
      <div className="h2-admin__tools">
        <p className="muted">
          {t('admin.overview.updated', {
            at: formatDate(data.generatedAt, i18n.language, 'INSTANT'),
          })}
        </p>
        <button
          type="button"
          className="btn"
          aria-disabled={refreshing || undefined}
          onClick={() => {
            if (!refreshing) onRefresh();
          }}
        >
          {t('admin.overview.refresh')}
        </button>
        {grafana && (
          <a href={grafana} target="_blank" rel="noopener noreferrer">
            {t('admin.overview.trends')}
          </a>
        )}
      </div>

      <h3 className="h2-admin__h3">{t('admin.overview.open', { count: open })}</h3>
      <ul className="h2-admin__counts">
        {data.openReviewItems.map(({ kind, count }) => (
          <li key={kind}>
            <Link to={`/admin/review?kind=${kind}`}>
              <span className="h2-admin__count">{count}</span> {t(`admin.kind.${kind}`)}
            </Link>
          </li>
        ))}
      </ul>

      <h3 className="h2-admin__h3">{t('admin.overview.scout')}</h3>
      <ul className="h2-admin__counts">
        <li>
          <Link to="/admin/enrichment">
            <span className="h2-admin__count">{data.enrichmentBacklog}</span>{' '}
            {t('admin.overview.backlog')}
          </Link>
        </li>
        <li>
          <Link to="/admin/runs">
            <span className="h2-admin__count">{data.failedRunsLast24h}</span>{' '}
            {t('admin.overview.failed')}
          </Link>
        </li>
      </ul>

      <HomeExampleCounts counts={data.homeExamples} />

      <p>
        <Link to="/admin/settings">
          {t('admin.overview.overrides', { count: data.overridesActive })}
        </Link>
      </p>

      <ScrollBox label={t('admin.overview.budgets')}>
        <table className="v4-table h2-admin__table" aria-labelledby="ov-budgets">
          <caption id="ov-budgets" className="h2-admin__caption">
            {t('admin.overview.budgets')}
          </caption>
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
                <td>
                  <Usage used={b.usedToday ?? null} limit={b.dailyLimit} />
                </td>
              </tr>
            ))}
            <tr>
              <th scope="row">{t('admin.overview.webCredits')}</th>
              <td>
                {data.webCredits ? (
                  <Usage
                    used={data.webCredits.usedToday}
                    limit={data.webCredits.dailyCap ?? null}
                  />
                ) : (
                  t('admin.overview.notCounted')
                )}
              </td>
            </tr>
          </tbody>
        </table>
      </ScrollBox>

      <h3 className="h2-admin__h3">{t('admin.overview.lastRuns')}</h3>
      {data.recentRuns.length === 0 ? (
        <AdminEmpty command="helper2 scouts --runs" text={t('admin.noRuns')} />
      ) : (
        <ul className="h2-admin__runs">
          {data.recentRuns.slice(0, SHOWN_RUNS).map((r) => (
            <li key={r.id}>
              <StatusPill tone={RUN_TONE[r.status]}>{t(`admin.runStatus.${r.status}`)}</StatusPill>{' '}
              {r.sourceId} · {formatDate(r.startedAt, i18n.language, 'INSTANT')}
            </li>
          ))}
        </ul>
      )}
      <p>
        <Link to="/admin/runs">{t('admin.overview.allRuns')}</Link>
      </p>
    </>
  );
}

/**
 * How many home examples are hidden now, per check and paused (ADR 0015), each a link to the Examples page filtered by
 * that check. A path with fewer shown than the home page picks gets the same warning as the Examples page.
 */
function HomeExampleCounts({ counts }: { counts: Overview['homeExamples'] }) {
  const { t } = useTranslation();
  const hidden = (outcome: ExampleCheckOutcome) =>
    counts.hidden.find((h) => h.outcome === outcome)?.count ?? 0;
  const shown = (path: AudiencePath) => counts.shown.find((s) => s.path === path)?.count ?? 0;
  const total = HIDING.reduce((sum, o) => sum + hidden(o), counts.paused);
  return (
    <>
      <h3 className="h2-admin__h3">{t('admin.overview.examples.hidden', { count: total })}</h3>
      <p className="muted">
        {t('admin.overview.examples.shown', {
          use: shown('USE_AI'),
          build: shown('BUILD_WITH_AI'),
        })}
      </p>
      {PATHS.filter((p) => shown(p) < SHOWN_PER_PATH).map((p) => (
        <p key={p} className="banner banner-warn">
          <Icon name="warn" /> {t('admin.examples.few', { path: t(`admin.examples.path.${p}`) })}{' '}
          <Link to="/admin/examples">{t('admin.overview.examples.open')}</Link>
        </p>
      ))}
      <ul className="h2-admin__counts">
        {HIDING.map((outcome) => (
          <li key={outcome}>
            <Link to={`/admin/examples?outcome=${outcome}`}>
              <span className="h2-admin__count">{hidden(outcome)}</span>{' '}
              {t(`admin.overview.examples.${outcome}`)}
            </Link>
          </li>
        ))}
        <li>
          <Link to="/admin/examples">
            <span className="h2-admin__count">{counts.paused}</span>{' '}
            {t('admin.overview.examples.paused')}
          </Link>
        </li>
      </ul>
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
      <meter
        min={0}
        max={limit}
        value={Math.min(used, limit)}
        high={limit * 0.8}
        aria-hidden="true"
      />
    </span>
  );
}
