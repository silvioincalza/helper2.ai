import './adminText';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import { ApiError } from '@/api/client';
import type {
  RelationshipTargetGroup as Group,
  RelationshipTargetGroupDecision as Decision,
} from '@/api/types';
import { Icon } from '@/ui/Icon';
import { RetryButton, StatePanel } from '@/ui/StatePanel';
import { ScrollBox } from '@/ui/ScrollBox';
import { useApi } from '@/hooks/useApi';
import { AdminEmpty, AdminLoading } from './AdminHead';
import { Pagination } from './Pagination';
import { ExistingPicker } from './RelationshipTarget';

const GROUP_PAGE_SIZE = 50;
const DETAIL_ID = 'rg-detail';
const COMMAND = 'helper2 review --open --by-target';

function errorOf(e: unknown, t: (k: string) => string): string {
  if (e instanceof ApiError) {
    const detail = e.problem?.detail;
    if (detail && (e.status === 400 || e.status === 404 || e.status === 409)) return detail;
  }
  return t('admin.groups.failed');
}

export function RelationshipGroups({
  page,
  onPage,
  onDecided,
}: {
  page: number;
  onPage: (page: number) => void;
  onDecided: () => void;
}) {
  const { t } = useTranslation();
  const groups = useApi(
    `relationship-groups/${page}`,
    (s) => adminApi.listRelationshipTargetGroups(page, GROUP_PAGE_SIZE, s),
    { cache: false },
  );
  const [selectedKey, setSelectedKey] = useState<string | undefined>();
  const [busy, setBusy] = useState(false);
  const [linking, setLinking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState('');
  const list = groups.data?.items ?? [];
  const selected = list.find((g) => g.key === selectedKey) ?? list[0];
  const total = groups.data?.total ?? 0;
  const lastPage = Math.max(0, Math.ceil(total / GROUP_PAGE_SIZE) - 1);
  const pastTheEnd = groups.data !== undefined && list.length === 0 && page > lastPage;

  const movedFrom = useRef<number | null>(null);
  useEffect(() => {
    if (!pastTheEnd || movedFrom.current === page) return;
    movedFrom.current = page;
    onPage(lastPage);
  }, [pastTheEnd, page, lastPage, onPage]);

  const decide = async (group: Group, decision: Omit<Decision, 'key'>) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const outcome = await adminApi.decideRelationshipTargetGroup({ key: group.key, ...decision });
      setDone(
        t('admin.groups.done', {
          name: group.name,
          count: outcome.decided,
          skipped: outcome.skipped,
          left: outcome.left,
        }),
      );
      setLinking(false);
      const at = list.indexOf(group);
      setSelectedKey((list[at + 1] ?? list[at - 1])?.key);
      groups.reload();
      onDecided();
    } catch (e) {
      setError(errorOf(e, t));
    } finally {
      setBusy(false);
    }
  };

  if (groups.loading || pastTheEnd) return <AdminLoading command={COMMAND} />;
  if (groups.error !== undefined)
    return (
      <StatePanel
        tone="error"
        level={3}
        title={t('admin.loadError')}
        next={<RetryButton onClick={groups.reload} />}
      />
    );
  if (!selected) return <AdminEmpty command={COMMAND} text={t('admin.queueEmpty')} />;

  return (
    <>
      <p role="status" className="h2-admin__bulk-result">
        {done}
      </p>
      <div className="h2-admin__review">
        <ScrollBox label={t('admin.groups.title')}>
          <table className="h2-table h2-admin__table h2-admin__list">
            <caption className="sr-only">
              {t('admin.groups.caption', { count: groups.data?.total ?? 0 })}
            </caption>
            <thead>
              <tr>
                <th scope="col">{t('admin.groups.col.name')}</th>
                <th scope="col" className="h2-num">
                  {t('admin.groups.col.items')}
                </th>
                <th scope="col">{t('admin.groups.col.examples')}</th>
              </tr>
            </thead>
            <tbody>
              {list.map((g) => {
                const current = g.key === selected.key;
                return (
                  <tr
                    key={g.key}
                    className={current ? 'h2-term-row h2-admin__row--current' : 'h2-term-row'}
                  >
                    <td className="h2-admin__what">
                      <button
                        type="button"
                        className="h2-admin__pick"
                        aria-current={current ? 'true' : undefined}
                        aria-controls={DETAIL_ID}
                        onClick={() => {
                          setSelectedKey(g.key);
                          setLinking(false);
                          setError(null);
                        }}
                      >
                        {g.name}
                      </button>
                    </td>
                    <td className="h2-num">{g.items}</td>
                    <td className="muted">{g.examples.join(' · ')}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </ScrollBox>
        <section id={DETAIL_ID} aria-labelledby="rg-detail-h" className="h2-admin__detail">
          <h3 id="rg-detail-h" className="h2-admin__reason">
            {selected.name}
          </h3>
          <p className="muted">
            {t('admin.groups.summary', { count: selected.items })} ·{' '}
            {selected.kinds.map((k) => t(`admin.groups.kind.${k}`)).join(', ')}
          </p>
          <p className="h2-admin__details">
            {t('admin.groups.examples')}: {selected.examples.join(', ')}
          </p>
          <p className="muted small">{t('admin.groups.why')}</p>
          {error && (
            <p role="alert" className="banner banner-warn">
              <Icon name="warn" /> {error}
            </p>
          )}
          <div className="h2-admin__actions">
            <button
              type="button"
              className="btn btn-primary"
              aria-disabled={busy || undefined}
              aria-expanded={linking}
              onClick={() => setLinking((v) => !v)}
            >
              {t('admin.groups.link')}
            </button>
            <button
              type="button"
              className="btn"
              aria-disabled={busy || undefined}
              onClick={() => void decide(selected, { decision: 'SERVICE' })}
            >
              {t('admin.groups.service', { count: selected.items })}
            </button>
            <button
              type="button"
              className="btn"
              aria-disabled={busy || undefined}
              onClick={() => void decide(selected, { decision: 'TOOL' })}
            >
              {t('admin.groups.tool', { count: selected.items })}
            </button>
            <button
              type="button"
              className="btn"
              aria-disabled={busy || undefined}
              onClick={() => void decide(selected, { decision: 'VENDOR' })}
            >
              {t('admin.groups.vendor', { count: selected.items })}
            </button>
            <button
              type="button"
              className="btn"
              aria-disabled={busy || undefined}
              onClick={() => void decide(selected, { decision: 'REJECT' })}
            >
              {t('admin.groups.reject', { count: selected.items })}
            </button>
          </div>
          {linking && (
            <div className="h2-admin__form">
              <ExistingPicker
                key={selected.key}
                initial={selected.name}
                saving={busy}
                onPick={(id) => void decide(selected, { decision: 'LINK', resourceId: id })}
              />
              <p className="muted small">{t('admin.groups.linkHint', { count: selected.items })}</p>
            </div>
          )}
        </section>
      </div>
      {groups.data && (
        <Pagination page={page} size={GROUP_PAGE_SIZE} total={groups.data.total} onPage={onPage} />
      )}
    </>
  );
}
