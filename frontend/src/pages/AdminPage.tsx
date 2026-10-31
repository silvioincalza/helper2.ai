import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '@/api/client';
import type { ReviewStatus, ScoutRun } from '@/api/types';
import { Icon } from '@/components/Icon';
import { Pagination } from '@/components/Pagination';
import { SignIn } from '@/components/SignIn';
import { useMe } from '@/context/MeContext';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { StatusPill, type StatusTone } from '@/components/ui/StatusPill';
import { useApi } from '@/hooks/useApi';
import { useRetry } from '@/hooks/useRetry';
import { useTitle } from '@/hooks/useTitle';
import { formatDate } from '@/lib/format';
import { resourcePath } from '@/lib/searchState';

/** How often the run listing is reloaded while a Scout run is RUNNING. */
export const RUN_POLL_MS = 3000;
const REVIEW_PAGE_SIZE = 20;
const REVIEW_TONE: Record<ReviewStatus, StatusTone> = { OPEN: 'next', ACCEPTED: 'ok', REJECTED: 'warn', SUPERSEDED: 'later' };
const RUN_TONE: Record<ScoutRun['status'], StatusTone> = { RUNNING: 'next', SUCCEEDED: 'ok', FAILED: 'warn', SKIPPED: 'later' };

export default function AdminPage() {
  const { t, i18n } = useTranslation();
  const { me, isAdmin, loaded } = useMe();
  const [params] = useSearchParams();
  const [status, setStatus] = useState<ReviewStatus>('OPEN');
  const [actionError, setActionError] = useState<'failed' | 'runInProgress' | null>(null);
  const [page, setPage] = useState(0);
  const [busy, setBusy] = useState(false);
  const items = useApi(
    `review/${status}/${page}/${isAdmin}`,
    (s) => (isAdmin ? api.listReviewItems(status, page, REVIEW_PAGE_SIZE, s) : Promise.resolve({ items: [], total: 0 })),
    { cache: false },
  );
  const { retry: retryItems, focusRef: queueHeading } = useRetry(items.reload, items.loading);
  const runs = useApi(`runs/${isAdmin}`, (s) => (isAdmin ? api.listScoutRuns(s) : Promise.resolve([])), { cache: false });
  useTitle(t('nav.admin'));

  // A run collects in the background after its start is accepted: follow it on the listing until no run is RUNNING.
  // While it reloads, the listing keeps showing the previous runs.
  const runList = runs.data ?? runs.previous ?? [];
  const pollRuns = runs.data?.some((r) => r.status === 'RUNNING') ?? false;
  const reloadRuns = runs.reload;
  useEffect(() => {
    if (!pollRuns) return;
    const timer = setTimeout(reloadRuns, RUN_POLL_MS);
    return () => clearTimeout(timer);
  }, [pollRuns, runs.data, reloadRuns]);

  if (!loaded) return <p role="status">{t('common.loading')}</p>;
  if (!isAdmin && !me.authenticated && me.loginProviders.length > 0) {
    return (
      <div className="page admin-page">
        <h1>{t('admin.title')}</h1>
        {params.get('signin') === 'failed' && <p role="alert" className="warn-line"><Icon name="warn" /> {t('admin.signInFailed')}</p>}
        <p className="lede">{t('admin.signInPrompt')}</p>
        <div className="admin-page__tools"><SignIn /></div>
      </div>
    );
  }
  if (!isAdmin) {
    return (
      <StatePanel tone="error" level={1} title={t('admin.title')} next={<Link to="/" className="btn">{t('notFound.home')}</Link>}>
        <p>{t('admin.forbidden')}</p>
      </StatePanel>
    );
  }

  /**
   * One action at a time: while a request runs, every action button is aria-disabled (it keeps focus) and a second
   * click does nothing, so an item is never resolved twice and the scout never started twice.
   */
  const act = async (request: () => Promise<unknown>, then: () => void) => {
    if (busy) return;
    setBusy(true);
    setActionError(null);
    try {
      await request();
      then();
    } catch (e) {
      setActionError(e instanceof ApiError && e.status === 409 ? 'runInProgress' : 'failed');
    } finally {
      setBusy(false);
    }
  };
  const resolve = (id: string, s: 'ACCEPTED' | 'REJECTED') => act(() => api.resolveReviewItem(id, { status: s }), items.reload);
  const runNow = () => act(() => api.startScoutRun(), runs.reload);
  const disabled = busy || undefined;

  return (
    <div className="page admin-page">
      <h1>{t('admin.title')}</h1>
      {actionError && <p role="alert" className="warn-line"><Icon name="warn" /> {t(actionError === 'runInProgress' ? 'admin.runInProgress' : 'admin.actionFailed')}</p>}

      <section aria-labelledby="rq-h" className="page-part">
        <h2 id="rq-h" ref={queueHeading} tabIndex={-1} className="preview-label">{t('admin.reviewQueue')}</h2>
        <div className="admin-page__tools">
          <label>
            {t('admin.status')}
            <select value={status} onChange={(e) => { setStatus(e.target.value as ReviewStatus); setPage(0); }}>
              {(['OPEN', 'ACCEPTED', 'REJECTED', 'SUPERSEDED'] as const).map((s) => <option key={s} value={s}>{t(`admin.reviewStatus.${s}`)}</option>)}
            </select>
          </label>
        </div>
        {items.loading ? (
          <div aria-busy="true">
            <span className="sr-only">{t('common.loading')}</span>
            {[0, 1, 2].map((i) => <div key={i} className="skeleton-row result-skeleton" aria-hidden="true" />)}
          </div>
        ) : items.error !== undefined ? (
          <StatePanel tone="error" level={3} title={t('admin.loadError')} next={<RetryButton onClick={retryItems} />} />
        ) : (items.data?.items ?? []).length === 0 ? <p className="muted">{t('admin.queueEmpty')}</p> : (
          <ul className="result-list" aria-labelledby="rq-h">
            {(items.data?.items ?? []).map((it) => (
              <li key={it.id} className="result-row item-row">
                <p className="result-row__meta">
                  <span>{t(`admin.kind.${it.kind}`)}</span>
                  <span>{formatDate(it.createdAt, i18n.language, 'INSTANT')}</span>
                  {it.resourceId && <Link to={resourcePath(it.resourceId)}>{it.resourceTitle ?? it.resourceId}</Link>}
                </p>
                <p className="admin-page__reason">{it.reason}</p>
                {it.details && <p className="admin-page__details">{it.details}</p>}
                {it.status === 'OPEN' ? (
                  <div className="item-row__actions">
                    <button type="button" className="btn btn-primary" aria-disabled={disabled} onClick={() => void resolve(it.id, 'ACCEPTED')}>{t('admin.accept')}<span className="sr-only"> {it.reason}</span></button>
                    <button type="button" className="btn" aria-disabled={disabled} onClick={() => void resolve(it.id, 'REJECTED')}>{t('admin.reject')}<span className="sr-only"> {it.reason}</span></button>
                  </div>
                ) : <p><StatusPill tone={REVIEW_TONE[it.status]}>{t(`admin.reviewStatus.${it.status}`)}</StatusPill></p>}
              </li>
            ))}
          </ul>
        )}
        {items.data && <Pagination page={page} size={REVIEW_PAGE_SIZE} total={items.data.total} onPage={setPage} />}
      </section>

      <section aria-labelledby="sr-h" className="page-part">
        <h2 id="sr-h" className="preview-label">{t('admin.scoutRuns')}</h2>
        <div className="admin-page__tools">
          <button type="button" className="btn btn-primary" aria-disabled={disabled} onClick={() => void runNow()}>{t('admin.runNow')}</button>
        </div>
        {runs.loading && runs.previous === undefined ? <p role="status">{t('common.loading')}</p> : runList.length === 0 ? <p className="muted">{t('admin.noRuns')}</p> : (
          <div className="page-table">
            <table aria-labelledby="sr-h">
              <thead>
                <tr>
                  <th scope="col">{t('sources.col.source')}</th>
                  <th scope="col">{t('admin.col.status')}</th>
                  <th scope="col">{t('admin.col.started')}</th>
                  <th scope="col">{t('admin.col.seen')}</th>
                  <th scope="col">{t('admin.col.changed')}</th>
                  <th scope="col">{t('admin.col.message')}</th>
                </tr>
              </thead>
              <tbody>
                {runList.map((r) => (
                  <tr key={r.id}>
                    <th scope="row">{r.sourceId}</th>
                    <td><StatusPill tone={RUN_TONE[r.status]}>{t(`admin.runStatus.${r.status}`)}</StatusPill></td>
                    <td>{formatDate(r.startedAt, i18n.language, 'INSTANT')}</td>
                    <td>{r.itemsSeen}</td>
                    <td>{r.itemsChanged}</td>
                    <td>{r.message ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
