import './adminText';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import { ApiError } from '@/api/client';
import type { EnrichmentQueueEntry as Entry, EnrichmentQueueView } from '@/api/types';
import { Icon } from '@/components/Icon';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { StatusPill } from '@/components/ui/StatusPill';
import { useApi } from '@/hooks/useApi';
import { useRetry } from '@/hooks/useRetry';
import { formatDate } from '@/lib/format';
import { resourcePath } from '@/lib/searchState';
import { oneOf, QUEUE_TONE, QUEUE_VIEWS } from './adminModel';

const PAGE_SIZE = 20;
const REASON_MAX = 200;

type Action = 'prioritise' | 'skip' | 'requeue';
/** What the last action did, or why it did not, in words for the admin. */
type Result =
  { ok: true; action: Action; title: string } | { ok: false; detail?: string | undefined };

/**
 * The enrichment queue (ADR 0026, UJ-9): the backlog in the order the Scout takes it, or the skipped resources, or
 * the ones out of retries. Each row can be moved to the top, skipped or requeued; an action changes the order only,
 * never the daily cap. The view and the server's cursor live in the address, so Back returns to the previous page.
 * One action at a time; after it, focus goes to the result, since the row may have left the view.
 */
export default function EnrichmentQueuePage() {
  const { t, i18n } = useTranslation();
  const [params, setParams] = useSearchParams();
  const view: EnrichmentQueueView = oneOf(params.get('view'), QUEUE_VIEWS) ?? 'WAITING';
  const after = params.get('after') ?? undefined;
  const queue = useApi(
    `admin/enrichment/${view}/${after ?? ''}`,
    (s) => adminApi.listEnrichmentQueue(view, after, PAGE_SIZE, s),
    { cache: false },
  );
  const { retry, focusRef: heading } = useRetry(queue.reload, queue.loading);
  const [busy, setBusy] = useState(false);
  const [skipping, setSkipping] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const [result, setResult] = useState<Result | null>(null);
  const resultRef = useRef<HTMLParagraphElement>(null);
  const [focusBack, setFocusBack] = useState<string | null>(null);
  // Stable, so the reason field takes focus once when the confirm opens, not on every render.
  const focusOnMount = useCallback((el: HTMLInputElement | null) => el?.focus(), []);

  useEffect(() => {
    if (result) resultRef.current?.focus();
  }, [result]);
  useEffect(() => {
    if (focusBack) document.getElementById(focusBack)?.focus();
  }, [focusBack]);

  const items = queue.data?.items ?? [];
  const next = queue.data?.next;
  const disabled = busy || undefined;

  const setView = (value: string) => {
    setResult(null);
    setSkipping(null);
    setParams(value === 'WAITING' ? {} : { view: value });
  };
  const goTo = (cursor: string | undefined) =>
    setParams({ ...(view === 'WAITING' ? {} : { view }), ...(cursor ? { after: cursor } : {}) });

  /** One action at a time; a 409 means the row changed meanwhile, so the page reloads either way. */
  const act = async (action: Action, e: Entry) => {
    if (busy) return;
    setBusy(true);
    setResult(null);
    try {
      if (action === 'skip') await adminApi.skipEnrichment(e.resourceId, reason);
      else if (action === 'prioritise') await adminApi.prioritiseEnrichment(e.resourceId);
      else await adminApi.requeueEnrichment(e.resourceId);
      setResult({ ok: true, action, title: e.title });
    } catch (err) {
      setResult({
        ok: false,
        detail: err instanceof ApiError ? err.problem?.detail : undefined,
      });
    } finally {
      setBusy(false);
      setSkipping(null);
      setReason('');
      queue.reload();
    }
  };
  const openSkip = (e: Entry) => {
    setResult(null);
    setReason('');
    setFocusBack(null);
    setSkipping(e.resourceId);
  };
  const cancelSkip = (e: Entry) => {
    setSkipping(null);
    setFocusBack(`skip-${e.resourceId}`);
  };

  return (
    <section aria-labelledby="eq-h" className="h2-admin__part">
      <h2 id="eq-h" ref={heading} tabIndex={-1} className="h2-admin__h">
        {t('admin.enrichment.title')}
      </h2>
      <p className="muted">{t(`admin.enrichment.lede.${view}`)}</p>
      <div className="h2-admin__tools">
        <label>
          {t('admin.enrichment.show')}
          <select value={view} onChange={(e) => setView(e.target.value)}>
            {QUEUE_VIEWS.map((v) => (
              <option key={v} value={v}>
                {t(`admin.enrichment.view.${v}`)}
              </option>
            ))}
          </select>
        </label>
      </div>
      {result?.ok === true && (
        <p ref={resultRef} tabIndex={-1} role="status" className="banner">
          {t(`admin.enrichment.done.${result.action}`, { title: result.title })}
        </p>
      )}
      {result?.ok === false && (
        <p ref={resultRef} tabIndex={-1} role="alert" className="banner banner-warn">
          <Icon name="warn" />{' '}
          {result.detail
            ? t('admin.enrichment.refused', { detail: result.detail })
            : t('admin.enrichment.failed')}
        </p>
      )}
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
        <p className="muted">{t(`admin.enrichment.empty.${view}`)}</p>
      ) : (
        <div className="table-wrap">
          <table aria-labelledby="eq-h">
            <thead>
              <tr>
                <th scope="col">{t('admin.enrichment.col.resource')}</th>
                <th scope="col">{t('admin.enrichment.col.state')}</th>
                <th scope="col">{t('admin.enrichment.col.discovered')}</th>
                <th scope="col">{t('admin.enrichment.col.failed')}</th>
                <th scope="col">{t('admin.enrichment.col.lastRun')}</th>
                <th scope="col">{t('admin.enrichment.col.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {items.map((e) => (
                <tr key={e.resourceId}>
                  <th scope="row">
                    <a href={resourcePath(e.resourceId, e.title)}>{e.title}</a>
                  </th>
                  <td>
                    <State entry={e} />
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
                  <td>
                    {skipping === e.resourceId ? (
                      <form
                        className="h2-admin__actions"
                        aria-label={t('admin.enrichment.confirmSkip', { title: e.title })}
                        onSubmit={(ev) => {
                          ev.preventDefault();
                          void act('skip', e);
                        }}
                      >
                        <span>{t('admin.enrichment.confirmSkip', { title: e.title })}</span>
                        <label>
                          {t('admin.enrichment.reason')}
                          <input
                            ref={focusOnMount}
                            value={reason}
                            maxLength={REASON_MAX}
                            aria-describedby="skip-reason-hint"
                            onChange={(ev) => setReason(ev.target.value)}
                          />
                        </label>
                        <span id="skip-reason-hint" className="muted small">
                          {t('admin.enrichment.reasonHint')}
                        </span>
                        <button type="button" className="btn" onClick={() => cancelSkip(e)}>
                          {t('admin.enrichment.cancel')}
                        </button>
                        <button type="submit" className="btn btn-primary" aria-disabled={disabled}>
                          {t('admin.enrichment.action.skip')}
                        </button>
                      </form>
                    ) : (
                      <div className="h2-admin__actions">
                        {e.state === 'WAITING' && (
                          <button
                            type="button"
                            className="btn"
                            aria-disabled={disabled}
                            onClick={() => void act('prioritise', e)}
                          >
                            {t('admin.enrichment.action.prioritise')}{' '}
                            <span className="sr-only">{e.title}</span>
                          </button>
                        )}
                        {(e.state === 'WAITING' || e.state === 'PRIORITISED') && (
                          <button
                            type="button"
                            id={`skip-${e.resourceId}`}
                            className="btn"
                            aria-disabled={disabled}
                            onClick={() => openSkip(e)}
                          >
                            {t('admin.enrichment.action.skip')}{' '}
                            <span className="sr-only">{e.title}</span>
                          </button>
                        )}
                        {(e.state === 'SKIPPED' || e.state === 'OUT_OF_RETRIES') && (
                          <button
                            type="button"
                            className="btn"
                            aria-disabled={disabled}
                            onClick={() => void act('requeue', e)}
                          >
                            {t('admin.enrichment.action.requeue')}{' '}
                            <span className="sr-only">{e.title}</span>
                          </button>
                        )}
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="h2-admin__tools">
        {after && (
          <button type="button" className="btn" onClick={() => goTo(undefined)}>
            {t('admin.enrichment.first')}
          </button>
        )}
        {next && (
          <button type="button" className="btn" onClick={() => goTo(next)}>
            {t('admin.enrichment.next')}
          </button>
        )}
      </div>
    </section>
  );
}

/** Where the resource stands, in words: at the top, why it waits, skipped (with when and why), out of retries. */
function State({ entry: e }: { entry: Entry }) {
  const { t, i18n } = useTranslation();
  if (e.state === 'SKIPPED') {
    return (
      <>
        <StatusPill tone={QUEUE_TONE.SKIPPED}>{t('admin.enrichment.state.SKIPPED')}</StatusPill>
        {e.skippedAt && (
          <span className="muted small"> {formatDate(e.skippedAt, i18n.language, 'INSTANT')}</span>
        )}
        {e.skipReason && <p className="h2-admin__details">{e.skipReason}</p>}
      </>
    );
  }
  if (e.state === 'OUT_OF_RETRIES') {
    return (
      <StatusPill tone={QUEUE_TONE.OUT_OF_RETRIES}>
        {t('admin.enrichment.state.OUT_OF_RETRIES')}
      </StatusPill>
    );
  }
  return (
    <>
      {e.state === 'PRIORITISED' && (
        <StatusPill tone={QUEUE_TONE.PRIORITISED}>
          {t('admin.enrichment.state.PRIORITISED')}
        </StatusPill>
      )}{' '}
      {e.waiting && (
        <StatusPill tone={QUEUE_TONE[e.waiting]}>
          {t(`admin.enrichment.waiting.${e.waiting}`)}
        </StatusPill>
      )}
    </>
  );
}
