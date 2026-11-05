import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '@/api/client';
import type { ScoutRun } from '@/api/types';
import { Icon } from '@/components/Icon';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { ScrollBox } from '@/components/ui/ScrollBox';
import { StatusPill } from '@/components/ui/StatusPill';
import { useApi } from '@/hooks/useApi';
import { useRetry } from '@/hooks/useRetry';
import { formatDate } from '@/lib/format';
import { AdminEmpty, AdminHead, AdminLoading } from './AdminHead';
import { RUN_TONE } from './adminModel';

const COMMAND = 'helper2 scouts --runs';

/** How often the run listing is reloaded while a Scout run is RUNNING. */
export const RUN_POLL_MS = 3000;

/** The ids of the runs that carry a source's Run now: its first run in the listing (the newest). */
function firstRunPerSource(runs: ScoutRun[]): Set<string> {
  const seen = new Set<string>();
  const first = new Set<string>();
  for (const r of runs) {
    if (seen.has(r.sourceId)) continue;
    seen.add(r.sourceId);
    first.add(r.id);
  }
  return first;
}

/**
 * The Scout runs as a table in the terminal style (ADR 0042), `$ helper2 scouts --runs · N` with N the runs listed, Run all now and Run now for one source. A started run is followed on the listing until
 * no run is RUNNING. One run holds the cluster-wide lock at a time, so every start waits while one is pending or running.
 */
export default function ScoutRunsPage() {
  const { t, i18n } = useTranslation();
  const [actionError, setActionError] = useState<'failed' | 'runInProgress' | null>(null);
  const [busy, setBusy] = useState(false);
  const runs = useApi('admin/runs', (s) => api.listScoutRuns(s), { cache: false });
  const { retry, focusRef: heading } = useRetry(runs.reload, runs.loading);

  // While it reloads, and after a reload that failed, the listing keeps showing the previous runs.
  const loaded = runs.data ?? runs.previous;
  const runList = loaded ?? [];
  const running = runList.some((r) => r.status === 'RUNNING');
  const pollRuns = runs.data?.some((r) => r.status === 'RUNNING') ?? false;
  const reloadRuns = runs.reload;
  useEffect(() => {
    if (!pollRuns) return;
    const timer = setTimeout(reloadRuns, RUN_POLL_MS);
    return () => clearTimeout(timer);
  }, [pollRuns, runs.data, reloadRuns]);

  const waiting = busy || running;
  /**
   * One start at a time: while it is pending or a run is RUNNING, the buttons are aria-disabled (they keep focus) and
   * a click does nothing. [sourceId] runs one source; without it every enabled collector runs.
   */
  const start = async (sourceId?: string) => {
    if (waiting) return;
    setBusy(true);
    setActionError(null);
    try {
      await api.startScoutRun(sourceId);
      runs.reload();
    } catch (e) {
      setActionError(e instanceof ApiError && e.status === 409 ? 'runInProgress' : 'failed');
    } finally {
      setBusy(false);
    }
  };

  const withRunNow = firstRunPerSource(runList);
  return (
    <section aria-labelledby="sr-h" className="h2-admin__part">
      <AdminHead
        id="sr-h"
        title={t('admin.scoutRuns')}
        command={COMMAND}
        total={loaded ? loaded.length : null}
        headingRef={heading}
      />
      {actionError && (
        <p role="alert" className="banner banner-warn">
          <Icon name="warn" />{' '}
          {t(actionError === 'runInProgress' ? 'admin.runInProgress' : 'admin.actionFailed')}
        </p>
      )}
      <div className="h2-admin__tools">
        <button
          type="button"
          className="v4-btn h2-admin__primary"
          aria-disabled={waiting || undefined}
          onClick={() => void start()}
        >
          {t('admin.runAll')}
        </button>
        {running && <p className="muted small">{t('admin.runWaits')}</p>}
      </div>
      {runs.loading && loaded === undefined ? (
        <AdminLoading command={COMMAND} />
      ) : runs.error !== undefined && loaded === undefined ? (
        <StatePanel
          tone="error"
          level={3}
          title={t('admin.runsLoadError')}
          next={<RetryButton onClick={retry} />}
        />
      ) : (
        <>
          {runs.error !== undefined && !runs.loading && (
            <div role="alert" className="banner banner-warn">
              <p>
                <Icon name="warn" /> {t('admin.runsRefreshError')}
              </p>
              <RetryButton onClick={retry} />
            </div>
          )}
          {runList.length === 0 ? (
            <AdminEmpty command={COMMAND} text={t('admin.noRuns')} />
          ) : (
            <ScrollBox label={t('admin.scoutRuns')}>
              <table className="v4-table h2-admin__table" aria-labelledby="sr-h">
                <thead>
                  <tr>
                    <th scope="col">{t('sources.col.source')}</th>
                    <th scope="col">{t('admin.col.status')}</th>
                    <th scope="col">{t('admin.col.started')}</th>
                    <th scope="col">{t('admin.col.seen')}</th>
                    <th scope="col">{t('admin.col.changed')}</th>
                    <th scope="col">{t('admin.col.message')}</th>
                    <th scope="col">{t('admin.col.run')}</th>
                  </tr>
                </thead>
                <tbody>
                  {runList.map((r) => (
                    <tr key={r.id} className="v4-row">
                      <th scope="row">{r.sourceId}</th>
                      <td>
                        <StatusPill tone={RUN_TONE[r.status]}>
                          {t(`admin.runStatus.${r.status}`)}
                        </StatusPill>
                      </td>
                      <td className="v4-nowrap">
                        {formatDate(r.startedAt, i18n.language, 'INSTANT')}
                      </td>
                      <td className="is-num">{r.itemsSeen}</td>
                      <td className="is-num">{r.itemsChanged}</td>
                      <td>{r.message ?? '—'}</td>
                      <td>
                        {withRunNow.has(r.id) && (
                          <button
                            type="button"
                            className="v4-btn"
                            aria-label={t('admin.runSource', { source: r.sourceId })}
                            aria-disabled={waiting || undefined}
                            onClick={() => void start(r.sourceId)}
                          >
                            {t('admin.runNow')}
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </ScrollBox>
          )}
        </>
      )}
    </section>
  );
}
