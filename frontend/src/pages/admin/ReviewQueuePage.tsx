import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '@/api/client';
import type { ReviewItem } from '@/api/types';
import { Icon } from '@/components/Icon';
import { Pagination } from '@/components/Pagination';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { ScrollBox } from '@/components/ui/ScrollBox';
import { StatusPill } from '@/components/ui/StatusPill';
import { useApi } from '@/hooks/useApi';
import { useRetry } from '@/hooks/useRetry';
import { formatDate } from '@/lib/format';
import { resourcePath } from '@/lib/searchState';
import { AdminEmpty, AdminHead, AdminLoading } from './AdminHead';
import { REVIEW_KINDS, REVIEW_STATUSES, REVIEW_TONE, oneOf } from './adminModel';

const PAGE_SIZE = 20;
/** The API's limit for a decision note. */
const NOTE_MAX = 1000;

/** What went wrong with the last decision: refused carries the server's reason, in its words. */
type ActionError = { kind: 'failed' | 'alreadyDecided' } | { kind: 'refused'; detail: string };

/**
 * A 409 is someone else's decision, except a duplicate draft: that item stays open, with the reason. A drafted
 * example refused for a rule (400) says which.
 */
function actionErrorOf(e: unknown): ActionError {
  if (!(e instanceof ApiError)) return { kind: 'failed' };
  const { code, detail } = e.problem ?? {};
  if ((code === 'duplicate' || code === 'invalid-request') && detail)
    return { kind: 'refused', detail };
  if (e.status === 409) return { kind: 'alreadyDecided' };
  return { kind: 'failed' };
}

/**
 * The review queue (UJ-9): a table in the terminal style (ADR 0042), `$ helper2 review --open · N` with N the
 * matching items on the server; the status as a word. Status, kind and page live in the address,
 * so the overview can link into a filtered queue and Back works. One decision at a time, with an optional note.
 */
export default function ReviewQueuePage() {
  const { t, i18n } = useTranslation();
  const [params, setParams] = useSearchParams();
  const status = oneOf(params.get('status'), REVIEW_STATUSES) ?? 'OPEN';
  const kind = oneOf(params.get('kind'), REVIEW_KINDS);
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0);
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [actionError, setActionError] = useState<ActionError | null>(null);
  const [busy, setBusy] = useState(false);
  const items = useApi(
    `review/${status}/${kind ?? ''}/${page}`,
    (s) => api.listReviewItems({ status, kind }, page, PAGE_SIZE, s),
    { cache: false },
  );
  const { retry, focusRef: heading } = useRetry(items.reload, items.loading);

  /** A new filter starts on the first page; the other filter stays. */
  const setFilter = (name: 'status' | 'kind' | 'page', value: string | undefined) => {
    const next = new URLSearchParams(params);
    if (value === undefined) next.delete(name);
    else next.set(name, value);
    if (name !== 'page') next.delete('page');
    setParams(next);
  };

  /**
   * One action at a time: while a request runs, every action button is aria-disabled (it keeps focus) and a second
   * click does nothing. An item someone else decided first (409) is not an error to retry: the list is reloaded. A
   * refused draft stays open, with the reason.
   */
  const resolve = async (item: ReviewItem, decision: 'ACCEPTED' | 'REJECTED') => {
    if (busy) return;
    setBusy(true);
    setActionError(null);
    try {
      const note = notes[item.id]?.trim();
      await api.resolveReviewItem(item.id, { status: decision, ...(note ? { note } : {}) });
      setNotes(({ [item.id]: _, ...rest }) => rest);
      items.reload();
    } catch (e) {
      const error = actionErrorOf(e);
      setActionError(error);
      if (error.kind === 'alreadyDecided') items.reload();
    } finally {
      setBusy(false);
    }
  };
  const disabled = busy || undefined;
  const list = items.data?.items ?? [];
  const command = `helper2 review --${status.toLowerCase()}`;

  return (
    <section aria-labelledby="rq-h" className="h2-admin__part">
      <AdminHead
        id="rq-h"
        title={t('admin.reviewQueue')}
        command={command}
        total={items.loading ? null : (items.data?.total ?? null)}
        headingRef={heading}
      />
      {actionError && (
        <p role="alert" className="banner banner-warn">
          <Icon name="warn" />{' '}
          {actionError.kind === 'refused'
            ? t('admin.refused', { detail: actionError.detail })
            : t(
                actionError.kind === 'alreadyDecided'
                  ? 'admin.alreadyDecided'
                  : 'admin.actionFailed',
              )}
        </p>
      )}
      <div className="h2-admin__tools">
        <label>
          {t('admin.status')}
          <select value={status} onChange={(e) => setFilter('status', e.target.value)}>
            {REVIEW_STATUSES.map((s) => (
              <option key={s} value={s}>
                {t(`admin.reviewStatus.${s}`)}
              </option>
            ))}
          </select>
        </label>
        <label>
          {t('admin.kindFilter')}
          <select
            value={kind ?? ''}
            onChange={(e) => setFilter('kind', e.target.value || undefined)}
          >
            <option value="">{t('admin.allKinds')}</option>
            {REVIEW_KINDS.map((k) => (
              <option key={k} value={k}>
                {t(`admin.kind.${k}`)}
              </option>
            ))}
          </select>
        </label>
      </div>
      {items.loading ? (
        <AdminLoading command={command} />
      ) : items.error !== undefined ? (
        <StatePanel
          tone="error"
          level={3}
          title={t('admin.loadError')}
          next={<RetryButton onClick={retry} />}
        />
      ) : list.length === 0 ? (
        <AdminEmpty command={command} text={t('admin.queueEmpty')} />
      ) : (
        <ScrollBox label={t('admin.reviewQueue')}>
          <table className="v4-table h2-admin__table h2-admin__queue" aria-labelledby="rq-h">
            <thead>
              <tr>
                <th scope="col">{t('admin.col.kind')}</th>
                <th scope="col">{t('admin.col.resource')}</th>
                <th scope="col">{t('admin.col.check')}</th>
                <th scope="col">{t('admin.col.opened')}</th>
                <th scope="col">{t('admin.col.decision')}</th>
              </tr>
            </thead>
            <tbody>
              {list.map((it) => (
                <tr key={it.id} className="v4-row">
                  <td className="v4-nowrap">{t(`admin.kind.${it.kind}`)}</td>
                  <td>
                    {it.resourceId ? (
                      <Link className="v4-title" to={resourcePath(it.resourceId)}>
                        {it.resourceTitle ?? it.resourceId}
                      </Link>
                    ) : (
                      '—'
                    )}
                  </td>
                  <td className="h2-admin__what">
                    <p className="h2-admin__reason">{it.reason}</p>
                    {it.kind === 'EXAMPLE_CANDIDATE' ? (
                      <DraftedExample details={it.details} />
                    ) : (
                      it.details && <p className="h2-admin__details">{it.details}</p>
                    )}
                  </td>
                  <td className="v4-nowrap">
                    {formatDate(it.createdAt, i18n.language, 'INSTANT')}
                  </td>
                  <td>
                    {it.status === 'OPEN' ? (
                      <div className="h2-admin__decide">
                        <label className="h2-admin__note">
                          {t('admin.note')}
                          <input
                            type="text"
                            maxLength={NOTE_MAX}
                            value={notes[it.id] ?? ''}
                            onChange={(e) => setNotes((n) => ({ ...n, [it.id]: e.target.value }))}
                          />
                        </label>
                        <div className="h2-admin__actions">
                          <button
                            type="button"
                            className="v4-btn h2-admin__primary"
                            aria-disabled={disabled}
                            onClick={() => void resolve(it, 'ACCEPTED')}
                          >
                            {t('admin.accept')}
                            <span className="sr-only"> {it.reason}</span>
                          </button>
                          <button
                            type="button"
                            className="v4-btn"
                            aria-disabled={disabled}
                            onClick={() => void resolve(it, 'REJECTED')}
                          >
                            {t('admin.reject')}
                            <span className="sr-only"> {it.reason}</span>
                          </button>
                        </div>
                      </div>
                    ) : (
                      <StatusPill tone={REVIEW_TONE[it.status]}>
                        {t(`admin.reviewStatus.${it.status}`)}
                      </StatusPill>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </ScrollBox>
      )}
      {items.data && (
        <Pagination
          page={page}
          size={PAGE_SIZE}
          total={items.data.total}
          onPage={(p) => setFilter('page', String(p))}
        />
      )}
    </section>
  );
}

/** A drafted example's details are JSON (label, query, path, model): shown as words, marked as AI-drafted. */
function DraftedExample({ details }: { details: string | null | undefined }) {
  const { t } = useTranslation();
  const draft = parseDraft(details);
  if (!draft) return details ? <p className="h2-admin__details">{details}</p> : null;
  return (
    <dl className="h2-admin__details h2-admin__draft" data-ai-generated="true">
      <dt>{t('admin.review.drafted.label')}</dt>
      <dd>{draft.label}</dd>
      <dt>{t('admin.review.drafted.query')}</dt>
      <dd>{draft.query}</dd>
      <dt>{t('admin.review.drafted.path')}</dt>
      <dd>{t(`admin.examples.path.${draft.path}`)}</dd>
      <dd className="h2-admin__draft-note">
        {t('admin.review.drafted.model', { model: draft.model })}
      </dd>
    </dl>
  );
}

interface Draft {
  label: string;
  query: string;
  path: 'USE_AI' | 'BUILD_WITH_AI';
  model: string;
}

function parseDraft(details: string | null | undefined): Draft | null {
  if (!details) return null;
  try {
    const d = JSON.parse(details) as Partial<Draft>;
    return typeof d.label === 'string' &&
      typeof d.query === 'string' &&
      (d.path === 'USE_AI' || d.path === 'BUILD_WITH_AI') &&
      typeof d.model === 'string'
      ? (d as Draft)
      : null;
  } catch {
    return null;
  }
}
