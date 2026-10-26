import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api } from '@/api/client';
import type { ReviewStatus } from '@/api/types';
import { SignIn } from '@/components/SignIn';
import { useMe } from '@/context/MeContext';
import { useApi } from '@/hooks/useApi';
import { useTitle } from '@/hooks/useTitle';
import { formatDate } from '@/lib/format';

export default function AdminPage() {
  const { t, i18n } = useTranslation();
  const { me, isAdmin, loaded } = useMe();
  const [params] = useSearchParams();
  const [status, setStatus] = useState<ReviewStatus>('OPEN');
  const [actionError, setActionError] = useState(false);
  const [busy, setBusy] = useState(false);
  const items = useApi(`review/${status}/${isAdmin}`, (s) => (isAdmin ? api.listReviewItems(status, s) : Promise.resolve([])));
  const runs = useApi(`runs/${isAdmin}`, (s) => (isAdmin ? api.listScoutRuns(s) : Promise.resolve([])));
  useTitle(t('nav.admin'));

  if (!loaded) return <p role="status">{t('common.loading')}</p>;
  if (!isAdmin && !me.authenticated && me.loginProviders.length > 0) {
    return (
      <div>
        <h1>{t('admin.title')}</h1>
        {params.get('signin') === 'failed' && <p role="alert" className="banner banner-warn">{t('admin.signInFailed')}</p>}
        <p>{t('admin.signInPrompt')}</p>
        <SignIn />
      </div>
    );
  }
  if (!isAdmin) return <p role="alert" className="state-box state-error">{t('admin.forbidden')}</p>;

  /**
   * One action at a time: while a request runs, every action button is aria-disabled (it keeps focus) and a second
   * click does nothing, so an item is never resolved twice and the scout never started twice.
   */
  const act = async (request: () => Promise<unknown>, then: () => void) => {
    if (busy) return;
    setBusy(true);
    setActionError(false);
    try {
      await request();
      then();
    } catch {
      setActionError(true);
    } finally {
      setBusy(false);
    }
  };
  const resolve = (id: string, s: 'ACCEPTED' | 'REJECTED') => act(() => api.resolveReviewItem(id, { status: s }), items.reload);
  const runNow = () => act(() => api.startScoutRun(), runs.reload);
  const disabled = busy || undefined;

  return (
    <div>
      <h1>{t('admin.title')}</h1>
      {actionError && <p role="alert" className="banner banner-warn">{t('admin.actionFailed')}</p>}

      <section aria-labelledby="rq-h" className="panel">
        <h2 id="rq-h">{t('admin.reviewQueue')}</h2>
        <label>
          {t('admin.status')}{' '}
          <select value={status} onChange={(e) => setStatus(e.target.value as ReviewStatus)}>
            {(['OPEN', 'ACCEPTED', 'REJECTED'] as const).map((s) => <option key={s} value={s}>{t(`admin.reviewStatus.${s}`)}</option>)}
          </select>
        </label>
        {items.loading ? <p role="status">{t('common.loading')}</p> : items.error !== undefined ? (
          <p role="alert">{t('admin.loadError')} <button type="button" className="btn" onClick={items.reload}>{t('common.retry')}</button></p>
        ) : (items.data ?? []).length === 0 ? <p className="muted">{t('admin.queueEmpty')}</p> : (
          <ul className="plain-list review-list">
            {(items.data ?? []).map((it) => (
              <li key={it.id} className="card">
                <p><span className="badge">{t(`admin.kind.${it.kind}`)}</span>{' '}
                  {it.resourceId ? <Link to={`/resource/${it.resourceId}`}>{it.resourceTitle ?? it.resourceId}</Link> : null}
                </p>
                <p>{it.reason}</p>
                {it.details && <p className="muted small">{it.details}</p>}
                <p className="muted small">{formatDate(it.createdAt, i18n.language, 'INSTANT')}</p>
                {it.status === 'OPEN' ? (
                  <div className="card-actions">
                    <button type="button" className="btn btn-primary" aria-disabled={disabled} onClick={() => void resolve(it.id, 'ACCEPTED')}>{t('admin.accept')}<span className="sr-only"> {it.reason}</span></button>
                    <button type="button" className="btn" aria-disabled={disabled} onClick={() => void resolve(it.id, 'REJECTED')}>{t('admin.reject')}<span className="sr-only"> {it.reason}</span></button>
                  </div>
                ) : <p>{t(`admin.reviewStatus.${it.status}`)}</p>}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section aria-labelledby="sr-h" className="panel">
        <h2 id="sr-h">{t('admin.scoutRuns')}</h2>
        <button type="button" className="btn btn-primary" aria-disabled={disabled} onClick={() => void runNow()}>{t('admin.runNow')}</button>
        {runs.loading ? <p role="status">{t('common.loading')}</p> : (runs.data ?? []).length === 0 ? <p className="muted">{t('admin.noRuns')}</p> : (
          <div className="table-wrap">
            <table>
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
                {(runs.data ?? []).map((r) => (
                  <tr key={r.id}>
                    <th scope="row">{r.sourceId}</th>
                    <td>{t(`admin.runStatus.${r.status}`)}</td>
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
