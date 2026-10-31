import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '@/api/client';
import type { ReviewStatus, ScoutRun } from '@/api/types';
import { Icon } from '@/components/Icon';
import { Pagination } from '@/components/Pagination';
import { SignIn } from '@/components/SignIn';
import { useMe } from '@/context/MeContext';
import '@/components/saved/saved-v2.css';
import { StatusPill, type StatusTone } from '@/components/ui/StatusPill';
import { useApi } from '@/hooks/useApi';
import { useRetry } from '@/hooks/useRetry';
import { useTitle } from '@/hooks/useTitle';
import { formatDate } from '@/lib/format';
import { resourcePath } from '@/lib/searchState';
import './admin-v2.css';

/** How often the run listing is reloaded while a Scout run is RUNNING. */
export const RUN_POLL_MS = 3000;
const REVIEW_PAGE_SIZE = 20;
/** Each status in words; the pill's tone only repeats it. */
const REVIEW_TONE: Record<ReviewStatus, StatusTone> = { OPEN: 'next', ACCEPTED: 'ok', REJECTED: 'warn', SUPERSEDED: 'later' };
const RUN_TONE: Record<ScoutRun['status'], StatusTone> = { RUNNING: 'next', SUCCEEDED: 'ok', FAILED: 'warn', SKIPPED: 'later' };

/**
 * The admin back office (ADR 0023) in the design (ADR 0040): the review queue as rows like the saved rows, the status
 * as a pill, the Scout runs as a table. Try again on the queue keeps focus on the queue's heading.
 */

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
  const runs = useApi(`runs/${isAdmin}`, (s) => (isAdmin ? api.listScoutRuns(s) : Promise.resolve([])), { cache: false });
  const { retry: retryItems, focusRef: queueHeading } = useRetry(items.reload, items.loading);
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
      <div className="h2-admin">
        <header className="h2-page-head">
          <h1>{t('admin.title')}</h1>
          <p className="h2-page-head__lede">{t('admin.signInPrompt')}</p>
        </header>
        {params.get('signin') === 'failed' && <p role="alert" className="banner banner-warn">{t('admin.signInFailed')}</p>}
        <div className="h2-admin__tools"><SignIn /></div>
      </div>
    );
  }
  if (!isAdmin) {
    return (
      <div className="h2-admin">
        <header className="h2-page-head"><h1>{t('admin.title')}</h1></header>
        <p role="alert" className="state-box state-error"><Icon name="warn" /> {t('admin.forbidden')}</p>
        <p><Link to="/" className="btn">{t('notFound.home')}</Link></p>
      </div>
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
    <div className="h2-admin">
      <header className="h2-page-head"><h1>{t('admin.title')}</h1></header>
      {actionError && <p role="alert" className="banner banner-warn"><Icon name="warn" /> {t(actionError === 'runInProgress' ? 'admin.runInProgress' : 'admin.actionFailed')}</p>}

      <section aria-labelledby="rq-h" className="h2-admin__part">
        <h2 id="rq-h" ref={queueHeading} tabIndex={-1} className="h2-admin__h">{t('admin.reviewQueue')}</h2>
        <div className="h2-admin__tools">
          <label>
            {t('admin.status')}
            <select value={status} onChange={(e) => { setStatus(e.target.value as ReviewStatus); setPage(0); }}>
              {(['OPEN', 'ACCEPTED', 'REJECTED', 'SUPERSEDED'] as const).map((s) => <option key={s} value={s}>{t(`admin.reviewStatus.${s}`)}</option>)}
            </select>
          </label>
        </div>
        {items.loading ? <p role="status" className="muted">{t('common.loading')}</p> : items.error !== undefined ? (
          <div role="alert" className="state-box state-error">
            <p><Icon name="warn" /> {t('admin.loadError')}</p>
            <button type="button" className="btn btn-primary" onClick={retryItems}><Icon name="refresh" /> {t('common.retry')}</button>
          </div>
        ) : (items.data?.items ?? []).length === 0 ? <p className="muted">{t('admin.queueEmpty')}</p> : (
          <ul className="h2-saved__list" aria-labelledby="rq-h">
            {(items.data?.items ?? []).map((it) => (
              <li key={it.id} className="h2-saved__row">
                <p className="h2-saved__meta">
                  <span>{t(`admin.kind.${it.kind}`)}</span>
                  <span>{formatDate(it.createdAt, i18n.language, 'INSTANT')}</span>
                  {it.resourceId && <Link to={resourcePath(it.resourceId)}>{it.resourceTitle ?? it.resourceId}</Link>}
                </p>
                <p className="h2-admin__reason">{it.reason}</p>
                {it.details && <p className="h2-admin__details">{it.details}</p>}
                {it.status === 'OPEN' ? (
                  <div className="h2-saved__actions h2-admin__actions">
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

      <section aria-labelledby="sr-h" className="h2-admin__part">
        <h2 id="sr-h" className="h2-admin__h">{t('admin.scoutRuns')}</h2>
        <div className="h2-admin__tools">
          <button type="button" className="btn btn-primary" aria-disabled={disabled} onClick={() => void runNow()}>{t('admin.runNow')}</button>
        </div>
        {runs.loading && runs.previous === undefined ? <p role="status">{t('common.loading')}</p> : runList.length === 0 ? <p className="muted">{t('admin.noRuns')}</p> : (
          <div className="table-wrap">
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
