import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api } from '@/api/client';
import type { ReviewKind } from '@/api/types';
import { StatusPill } from '@/components/ui/StatusPill';
import { useApi } from '@/hooks/useApi';
import { formatDate } from '@/lib/format';
import { REVIEW_KINDS, RUN_TONE } from './adminModel';

/**
 * The landing page: how many items wait, per kind, each a link into the queue filtered by that kind, and the last
 * Scout run. A first step towards the dashboard; budgets and the enrichment backlog come with its own endpoint.
 */
export default function AdminOverview() {
  const { t, i18n } = useTranslation();
  // One small request per kind (size 1: only the total is read). Five kinds, one admin: no endpoint of its own yet.
  const open = useApi('admin/overview/open', async (s) => {
    const totals = await Promise.all(REVIEW_KINDS.map((kind) => api.listReviewItems({ status: 'OPEN', kind }, 0, 1, s).then((p) => p.total)));
    return REVIEW_KINDS.map((kind, i) => ({ kind, total: totals[i] ?? 0 }));
  }, { cache: false });
  const runs = useApi('admin/overview/runs', (s) => api.listScoutRuns(s), { cache: false });
  const last = runs.data?.[0];

  return (
    <>
      <section aria-labelledby="ov-open-h" className="h2-admin__part">
        <h2 id="ov-open-h" className="h2-admin__h">{t('admin.overview.open')}</h2>
        {open.loading ? <p role="status" className="muted">{t('common.loading')}</p> : open.error !== undefined ? (
          <p role="alert" className="banner banner-warn">{t('admin.loadError')}</p>
        ) : (
          <ul className="h2-admin__counts">
            {(open.data ?? []).map(({ kind, total }: { kind: ReviewKind; total: number }) => (
              <li key={kind}>
                <Link to={`/admin/review?kind=${kind}`}>
                  <span className="h2-admin__count">{total}</span> {t(`admin.kind.${kind}`)}
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
      <section aria-labelledby="ov-run-h" className="h2-admin__part">
        <h2 id="ov-run-h" className="h2-admin__h">{t('admin.overview.lastRun')}</h2>
        {runs.loading ? <p role="status" className="muted">{t('common.loading')}</p> : !last ? <p className="muted">{t('admin.noRuns')}</p> : (
          <p>
            <StatusPill tone={RUN_TONE[last.status]}>{t(`admin.runStatus.${last.status}`)}</StatusPill>{' '}
            {last.sourceId} · {formatDate(last.startedAt, i18n.language, 'INSTANT')} · <Link to="/admin/runs">{t('admin.overview.allRuns')}</Link>
          </p>
        )}
      </section>
    </>
  );
}
