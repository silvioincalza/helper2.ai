import './adminText';
import { useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import { Pagination } from '@/admin/Pagination';
import { RetryButton, StatePanel } from '@/ui/StatePanel';
import { ScrollBox } from '@/ui/ScrollBox';
import { useApi } from '@/hooks/useApi';
import { useRetry } from '@/hooks/useRetry';
import { formatDate } from '@/lib/format';
import { AdminEmpty, AdminHead, AdminLoading } from './AdminHead';

const PAGE_SIZE = 20;
const COMMAND = 'helper2 audit';

export default function AuditPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0);
  const entries = useApi(
    `admin/audit/${page}`,
    (s) => adminApi.listAuditEntries(page, PAGE_SIZE, s),
    {
      cache: false,
    },
  );
  const { retry, focusRef: heading } = useRetry(entries.reload, entries.loading);
  const list = entries.data?.items ?? [];

  return (
    <section aria-labelledby="au-h" className="h2-admin__part">
      <AdminHead
        id="au-h"
        title={t('admin.audit.title')}
        command={COMMAND}
        total={entries.loading ? null : (entries.data?.total ?? null)}
        headingRef={heading}
      />
      {entries.loading ? (
        <AdminLoading command={COMMAND} />
      ) : entries.error !== undefined ? (
        <StatePanel
          tone="error"
          level={3}
          title={t('admin.audit.loadError')}
          next={<RetryButton onClick={retry} />}
        />
      ) : list.length === 0 ? (
        <AdminEmpty command={COMMAND} text={t('admin.audit.empty')} />
      ) : (
        <ScrollBox label={t('admin.audit.title')}>
          <table className="h2-table h2-admin__table" aria-labelledby="au-h">
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
                <tr key={e.id} className="h2-term-row">
                  <th scope="row">{formatDate(e.at, 'INSTANT')}</th>
                  <td>{t(`admin.audit.actions.${e.action}`)}</td>
                  <td className="h2-admin__id">{e.targetId ?? '—'}</td>
                  <td>
                    {e.outcome}
                    {(e.before || e.after) && (
                      <>
                        {' · '}
                        {t('admin.audit.change', {
                          before: e.before ?? '—',
                          after: e.after ?? '—',
                        })}
                      </>
                    )}
                    {e.reason && <span className="muted"> · {e.reason}</span>}
                  </td>
                  <td>{e.adminId}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </ScrollBox>
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
