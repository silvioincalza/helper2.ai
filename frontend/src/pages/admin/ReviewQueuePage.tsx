import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '@/api/client';
import type { ReviewItem } from '@/api/types';
import { Icon } from '@/components/Icon';
import { Pagination } from '@/components/Pagination';
import '@/components/saved/saved.css';
import { RetryButton, StatePanel } from '@/components/ui/StatePanel';
import { StatusPill } from '@/components/ui/StatusPill';
import { useApi } from '@/hooks/useApi';
import { useRetry } from '@/hooks/useRetry';
import { formatDate } from '@/lib/format';
import { resourcePath } from '@/lib/searchState';
import { REVIEW_KINDS, REVIEW_STATUSES, REVIEW_TONE, oneOf } from './adminModel';

const PAGE_SIZE = 20;
/** The API's limit for a decision note. */
const NOTE_MAX = 1000;

type ActionError = 'failed' | 'alreadyDecided';

/**
 * The review queue (UJ-9): rows like the saved rows, the status as a pill. Status, kind and page live in the address,
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
   * click does nothing. An item someone else decided first (409) is not an error to retry: the list is reloaded.
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
      const conflict = e instanceof ApiError && e.status === 409;
      setActionError(conflict ? 'alreadyDecided' : 'failed');
      if (conflict) items.reload();
    } finally {
      setBusy(false);
    }
  };
  const disabled = busy || undefined;
  const list = items.data?.items ?? [];

  return (
    <section aria-labelledby="rq-h" className="h2-admin__part">
      <h2 id="rq-h" ref={heading} tabIndex={-1} className="h2-admin__h">
        {t('admin.reviewQueue')}
      </h2>
      {actionError && (
        <p role="alert" className="banner banner-warn">
          <Icon name="warn" />{' '}
          {t(actionError === 'alreadyDecided' ? 'admin.alreadyDecided' : 'admin.actionFailed')}
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
        <p role="status" className="muted">
          {t('common.loading')}
        </p>
      ) : items.error !== undefined ? (
        <StatePanel
          tone="error"
          level={3}
          title={t('admin.loadError')}
          next={<RetryButton onClick={retry} />}
        />
      ) : list.length === 0 ? (
        <p className="muted">{t('admin.queueEmpty')}</p>
      ) : (
        <ul className="h2-saved__list" aria-labelledby="rq-h">
          {list.map((it) => (
            <li key={it.id} className="h2-saved__row">
              <p className="h2-saved__meta">
                <span>{t(`admin.kind.${it.kind}`)}</span>
                <span>{formatDate(it.createdAt, i18n.language, 'INSTANT')}</span>
                {it.resourceId && (
                  <Link to={resourcePath(it.resourceId)}>{it.resourceTitle ?? it.resourceId}</Link>
                )}
              </p>
              <p className="h2-admin__reason">{it.reason}</p>
              {it.details && <p className="h2-admin__details">{it.details}</p>}
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
                  <div className="h2-saved__actions h2-admin__actions">
                    <button
                      type="button"
                      className="btn btn-primary"
                      aria-disabled={disabled}
                      onClick={() => void resolve(it, 'ACCEPTED')}
                    >
                      {t('admin.accept')}
                      <span className="sr-only"> {it.reason}</span>
                    </button>
                    <button
                      type="button"
                      className="btn"
                      aria-disabled={disabled}
                      onClick={() => void resolve(it, 'REJECTED')}
                    >
                      {t('admin.reject')}
                      <span className="sr-only"> {it.reason}</span>
                    </button>
                  </div>
                </div>
              ) : (
                <p>
                  <StatusPill tone={REVIEW_TONE[it.status]}>
                    {t(`admin.reviewStatus.${it.status}`)}
                  </StatusPill>
                </p>
              )}
            </li>
          ))}
        </ul>
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
