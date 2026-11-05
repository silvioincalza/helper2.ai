import { Link, useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api } from '@/api/client';
import type { EnrichmentQueuePage as QueuePage } from '@/api/types';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { StatusPill, type StatusTone } from '@/components/ui/StatusPill';
import { useApi } from '@/hooks/useApi';
import { useRetry } from '@/hooks/useRetry';
import { formatDate } from '@/lib/format';
import { resourcePath } from '@/lib/searchState';

const PAGE_SIZE = 20;

type Waiting = QueuePage['items'][number]['waiting'];
/** Each reason in words; the tone only repeats it. */
const WAITING_TONE: Record<Waiting, StatusTone> = {
  NEW: 'next',
  MODEL_UNAVAILABLE: 'later',
  RETRY: 'warn',
};

/**
 * The enrichment backlog (ADR 0045), in the order the Scout takes it. Read-only. Paged by the
 * server's cursor, kept in the address, so Back returns to the previous page.
 */
export default function EnrichmentQueuePage() {
  const { t, i18n } = useTranslation();
  const [params, setParams] = useSearchParams();
  const after = params.get('after') ?? undefined;
  const queue = useApi(
    `admin/enrichment/${after ?? ''}`,
    (s) => api.listEnrichmentQueue(after, PAGE_SIZE, s),
    { cache: false },
  );
  const { retry, focusRef: heading } = useRetry(queue.reload, queue.loading);
  const items = queue.data?.items ?? [];
  const next = queue.data?.next;

  return (
    <section aria-labelledby="eq-h" className="h2-admin__part">
      <h2 id="eq-h" ref={heading} tabIndex={-1} className="h2-admin__h">
        {t('admin.enrichment.title')}
      </h2>
      <p className="muted">{t('admin.enrichment.lede')}</p>
      {queue.loading ? (
        <p role="status" className="muted">
          {t('common.loading')}
        </p>
      ) : queue.error !== undefined ? (
        <StatePanel
          tone="error"
          level={3}
          title={t('admin.enrichment.loadError')}
          next={<RetryButton onClick={retry} />}
        />
      ) : items.length === 0 ? (
        <p className="muted">{t('admin.enrichment.empty')}</p>
      ) : (
        <div className="table-wrap">
          <table aria-labelledby="eq-h">
            <thead>
              <tr>
                <th scope="col">{t('admin.enrichment.col.resource')}</th>
                <th scope="col">{t('admin.enrichment.col.waiting')}</th>
                <th scope="col">{t('admin.enrichment.col.discovered')}</th>
                <th scope="col">{t('admin.enrichment.col.failed')}</th>
                <th scope="col">{t('admin.enrichment.col.lastRun')}</th>
              </tr>
            </thead>
            <tbody>
              {items.map((e) => (
                <tr key={e.resourceId}>
                  <th scope="row">
                    <Link to={resourcePath(e.resourceId)}>{e.title}</Link>
                  </th>
                  <td>
                    <StatusPill tone={WAITING_TONE[e.waiting]}>
                      {t(`admin.enrichment.waiting.${e.waiting}`)}
                    </StatusPill>
                  </td>
                  <td>{formatDate(e.firstDiscoveredAt, i18n.language, 'INSTANT')}</td>
                  <td>{e.failedRuns}</td>
                  <td>
                    {e.lastOutcome && e.lastRunAt
                      ? t('admin.enrichment.lastRun', {
                          outcome: e.lastOutcome,
                          at: formatDate(e.lastRunAt, i18n.language, 'INSTANT'),
                        })
                      : t('admin.enrichment.noRun')}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="h2-admin__tools">
        {after && (
          <button type="button" className="btn" onClick={() => setParams({})}>
            {t('admin.enrichment.first')}
          </button>
        )}
        {next && (
          <button type="button" className="btn" onClick={() => setParams({ after: next })}>
            {t('admin.enrichment.next')}
          </button>
        )}
      </div>
    </section>
  );
}
