import './adminText';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import { api, ApiError } from '@/api/client';
import type { EnrichmentRecheck, ScoutRun } from '@/api/types';
import { Icon } from '@/ui/Icon';
import { RetryButton, StatePanel } from '@/ui/StatePanel';
import { ScrollBox } from '@/ui/ScrollBox';
import { StatusPill } from '@/ui/StatusPill';
import { useApi } from '@/hooks/useApi';
import { useRetry } from '@/hooks/useRetry';
import { formatDate } from '@/lib/format';
import { AdminEmpty, AdminHead, AdminLoading } from './AdminHead';
import { RUN_TONE } from './adminModel';
import { CategoryBackfill } from './CategoryBackfill';

const COMMAND = 'helper2 scouts --runs';

export const RUN_POLL_MS = 3000;

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

export default function ScoutRunsPage() {
  const { t } = useTranslation();
  const [actionError, setActionError] = useState<'failed' | 'runInProgress' | null>(null);
  const [busy, setBusy] = useState(false);
  const [recheck, setRecheck] = useState<
    EnrichmentRecheck | 'busy' | 'failed' | 'runInProgress' | null
  >(null);
  const runs = useApi('admin/runs', (s) => adminApi.listScoutRuns(s), { cache: false });
  const { retry, focusRef: heading } = useRetry(runs.reload, runs.loading);

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
  const start = async (sourceId?: string) => {
    if (waiting) return;
    setBusy(true);
    setActionError(null);
    try {
      await adminApi.startScoutRun(sourceId);
      runs.reload();
    } catch (e) {
      setActionError(e instanceof ApiError && e.status === 409 ? 'runInProgress' : 'failed');
    } finally {
      setBusy(false);
    }
  };

  const recheckAnswers = async () => {
    if (recheck === 'busy') return;
    setRecheck('busy');
    try {
      setRecheck(await adminApi.recheckEnrichments());
    } catch (e) {
      setRecheck(e instanceof ApiError && e.status === 409 ? 'runInProgress' : 'failed');
    }
  };

  const withRunNow = firstRunPerSource(runList);
  const sources = useApi('sources', (s) => api.listSources(s));
  const ran = new Set(runList.map((r) => r.sourceId));
  const neverRan =
    loaded === undefined
      ? []
      : (sources.data ?? sources.previous ?? []).filter(
          (s) => s.external && s.mode === 'INDEXED' && s.enabled && !ran.has(s.id),
        );
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
          className="btn btn-primary"
          aria-disabled={waiting || undefined}
          onClick={() => void start()}
        >
          {t('admin.runAll')}
        </button>
        <button
          type="button"
          className="btn"
          aria-disabled={recheck === 'busy' || undefined}
          onClick={() => void recheckAnswers()}
        >
          {t('admin.recheck')}
        </button>
        {running && <p className="muted small">{t('admin.runWaits')}</p>}
        {(recheck === 'failed' || recheck === 'runInProgress') && (
          <p role="alert" className="muted small">
            {t(recheck === 'failed' ? 'admin.actionFailed' : 'admin.recheckRunInProgress')}
          </p>
        )}
        {recheck !== null && typeof recheck === 'object' && (
          <p role="status" className="muted small">
            {t('admin.recheckDone', recheck)}
          </p>
        )}
        <CategoryBackfill />
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
          {runList.length === 0 && neverRan.length === 0 ? (
            <AdminEmpty command={COMMAND} text={t('admin.noRuns')} />
          ) : (
            <ScrollBox label={t('admin.scoutRuns')}>
              <table className="h2-table h2-admin__table" aria-labelledby="sr-h">
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
                    <tr key={r.id} className="h2-term-row">
                      <th scope="row">{r.sourceId}</th>
                      <td>
                        <StatusPill tone={RUN_TONE[r.status]}>
                          {t(`admin.runStatus.${r.status}`)}
                        </StatusPill>
                      </td>
                      <td className="h2-nowrap">{formatDate(r.startedAt, 'INSTANT')}</td>
                      <td className="is-num">{r.itemsSeen}</td>
                      <td className="is-num">{r.itemsChanged}</td>
                      <td>{r.message ?? '—'}</td>
                      <td>
                        {withRunNow.has(r.id) && (
                          <button
                            type="button"
                            className="btn"
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
                {neverRan.length > 0 && (
                  <tbody>
                    {neverRan.map((s) => (
                      <tr key={s.id} className="h2-term-row">
                        <th scope="row">{s.id}</th>
                        <td>
                          <StatusPill tone="later">{t('admin.neverRan')}</StatusPill>
                        </td>
                        <td>—</td>
                        <td className="is-num">—</td>
                        <td className="is-num">—</td>
                        <td>—</td>
                        <td>
                          <button
                            type="button"
                            className="btn"
                            aria-label={t('admin.runSource', { source: s.id })}
                            aria-disabled={waiting || undefined}
                            onClick={() => void start(s.id)}
                          >
                            {t('admin.runNow')}
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                )}
              </table>
            </ScrollBox>
          )}
        </>
      )}
    </section>
  );
}
