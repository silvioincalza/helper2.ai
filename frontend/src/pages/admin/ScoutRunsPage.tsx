import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '@/api/client';
import { Icon } from '@/components/Icon';
import { StatusPill } from '@/components/ui/StatusPill';
import { useApi } from '@/hooks/useApi';
import { formatDate } from '@/lib/format';
import { RUN_TONE } from './adminModel';

/** How often the run listing is reloaded while a Scout run is RUNNING. */
export const RUN_POLL_MS = 3000;

/** The Scout runs as a table, and Run now. A started run is followed on the listing until no run is RUNNING. */
export default function ScoutRunsPage() {
  const { t, i18n } = useTranslation();
  const [actionError, setActionError] = useState<'failed' | 'runInProgress' | null>(null);
  const [busy, setBusy] = useState(false);
  const runs = useApi('admin/runs', (s) => api.listScoutRuns(s), { cache: false });

  // While it reloads, the listing keeps showing the previous runs.
  const runList = runs.data ?? runs.previous ?? [];
  const pollRuns = runs.data?.some((r) => r.status === 'RUNNING') ?? false;
  const reloadRuns = runs.reload;
  useEffect(() => {
    if (!pollRuns) return;
    const timer = setTimeout(reloadRuns, RUN_POLL_MS);
    return () => clearTimeout(timer);
  }, [pollRuns, runs.data, reloadRuns]);

  /** One start at a time: while it runs, the button is aria-disabled (it keeps focus) and a second click does nothing. */
  const runNow = async () => {
    if (busy) return;
    setBusy(true);
    setActionError(null);
    try {
      await api.startScoutRun();
      runs.reload();
    } catch (e) {
      setActionError(e instanceof ApiError && e.status === 409 ? 'runInProgress' : 'failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-labelledby="sr-h" className="h2-admin__part">
      <h2 id="sr-h" className="h2-admin__h">{t('admin.scoutRuns')}</h2>
      {actionError && <p role="alert" className="banner banner-warn"><Icon name="warn" /> {t(actionError === 'runInProgress' ? 'admin.runInProgress' : 'admin.actionFailed')}</p>}
      <div className="h2-admin__tools">
        <button type="button" className="btn btn-primary" aria-disabled={busy || undefined} onClick={() => void runNow()}>{t('admin.runNow')}</button>
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
  );
}
