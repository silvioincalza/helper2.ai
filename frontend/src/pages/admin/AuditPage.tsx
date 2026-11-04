import { useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api } from '@/api/client';
import { Pagination } from '@/components/Pagination';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { useApi } from '@/hooks/useApi';
import { useRetry } from '@/hooks/useRetry';
import { formatDate } from '@/lib/format';

const PAGE_SIZE = 20;

/** What administrators did, newest first (ADR 0023, 7): read-only, paged, the page in the address. */
export default function AuditPage() {
  const { t, i18n } = useTranslation();
  const [params, setParams] = useSearchParams();
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0);
  const entries = useApi(`admin/audit/${page}`, (s) => api.listAuditEntries(page, PAGE_SIZE, s), {
    cache: false,
  });
  const { retry, focusRef: heading } = useRetry(entries.reload, entries.loading);
  const list = entries.data?.items ?? [];

  return (
    <section aria-labelledby="au-h" className="h2-admin__part">
      <h2 id="au-h" ref={heading} tabIndex={-1} className="h2-admin__h">
        {t('admin.audit.title')}
      </h2>
      {entries.loading ? (
        <p role="status" className="muted">
          {t('common.loading')}
        </p>
      ) : entries.error !== undefined ? (
        <StatePanel
          tone="error"
          level={3}
          title={t('admin.audit.loadError')}
          next={<RetryButton onClick={retry} />}
        />
      ) : list.length === 0 ? (
        <p className="muted">{t('admin.audit.empty')}</p>
      ) : (
        <div className="table-wrap">
          <table aria-labelledby="au-h">
            <thead>
              <tr>
                <th scope="col">{t('admin.audit.at')}</th>
                <th scope="col">{t('admin.audit.action')}</th>
                <th scope="col">{t('admin.audit.target')}</th>
                <th scope="col">{t('admin.audit.outcome')}</th>
                <th scope="col">{t('admin.audit.admin')}</th>
              </tr>
            </thead>
            <tbody>
              {list.map((e) => (
                <tr key={e.id}>
                  <th scope="row">{formatDate(e.at, i18n.language, 'INSTANT')}</th>
                  <td>{t(`admin.audit.actions.${e.action}`)}</td>
                  <td className="h2-admin__id">{e.targetId ?? '—'}</td>
                  <td>{e.outcome}</td>
                  <td>{e.adminId}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {entries.data && (
        <Pagination
          page={page}
          size={PAGE_SIZE}
          total={entries.data.total}
          onPage={(p) => setParams({ page: String(p) })}
        />
      )}
    </section>
  );
}
