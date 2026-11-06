import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
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
import { categoryOf } from '@/components/sources/DiscoveryCategories';
import { AdminEmpty, AdminHead, AdminLoading } from './AdminHead';
import { REVIEW_KINDS, REVIEW_STATUSES, REVIEW_TONE, oneOf } from './adminModel';
import { BulkBar, bulkSummary, type Decision } from './ReviewBulk';
import { ReviewEvidencePanel } from './ReviewEvidence';
import { ItemDetails } from './ReviewItemDetails';
import { ReviewKeysHelp } from './ReviewKeysHelp';

const PAGE_SIZE = 20;
/** A category id, as the API takes it. */
const CATEGORY = /^[a-z0-9]+(-[a-z0-9]+)*$/;
/** The API's limit for a decision note. */
const NOTE_MAX = 1000;
const DETAIL_ID = 'rq-detail';

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

/** A key typed into a field is text, not a command; a key with a modifier belongs to the browser. A checkbox is no field. */
function isCommandKey(e: KeyboardEvent): boolean {
  const target = e.target as HTMLElement;
  const box = target instanceof HTMLInputElement && target.type === 'checkbox';
  const typing =
    target.isContentEditable || (!box && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName));
  return !typing && !e.ctrlKey && !e.metaKey && !e.altKey;
}

/** The picks still open in [list]: the same map when none dropped out. */
function openPicks(
  picked: ReadonlyMap<string, ReviewItem>,
  list: ReviewItem[],
): ReadonlyMap<string, ReviewItem> {
  const open = new Set(list.filter((i) => i.status === 'OPEN').map((i) => i.id));
  const kept = [...picked].filter(([id]) => open.has(id));
  return kept.length === picked.size ? picked : new Map(kept);
}

/** The item after [id] in [list], else the one before it: what to review next once [id] is decided. */
function nextAfter(list: ReviewItem[], id: string): string | undefined {
  const at = list.findIndex((i) => i.id === id);
  return (list[at + 1] ?? list[at - 1])?.id;
}

/**
 * The review queue (UJ-9): a list and the selected item next to it (stacked on a narrow screen), in the terminal
 * style (ADR 0042), `$ helper2 review --open · N` with N the matching items on the server. Status, kind and page live
 * in the address, so the overview can link into a filtered queue and Back works. The selected item shows its details,
 * its evidence (ADR 0045) and the decision, with an optional note. Keys work only while focus is in the queue
 * (WCAG 2.1.4): j and k move, x picks, a accepts, r rejects, n goes to the note, ? lists them. A focused checkbox
 * selects its row, so a key typed on it acts on that row. After a decision the
 * next item is selected and takes focus, and a polite status says what is left. Picked open items can be decided
 * together from the bar under the list (ResolveReviewItems): the server decides each on its own and leaves open what
 * it may not accept in bulk; the items that failed stay picked.
 */
export default function ReviewQueuePage() {
  const { t, i18n } = useTranslation();
  const [params, setParams] = useSearchParams();
  const status = oneOf(params.get('status'), REVIEW_STATUSES) ?? 'OPEN';
  const kind = oneOf(params.get('kind'), REVIEW_KINDS);
  const wanted = params.get('category');
  const category = wanted && CATEGORY.test(wanted) ? wanted : undefined;
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0);
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [actionError, setActionError] = useState<ActionError | null>(null);
  const [busy, setBusy] = useState(false);
  const [selectedId, setSelectedId] = useState<string | undefined>();
  const [announcement, setAnnouncement] = useState('');
  const [help, setHelp] = useState(false);
  /** Open items picked for a bulk decision, by id; kept across reloads until decided or cleared. */
  const [pickedIds, setPicked] = useState<ReadonlyMap<string, ReviewItem>>(new Map());
  const [bulkResult, setBulkResult] = useState('');
  /** Set by a decision: once the list has reloaded, the selected row takes focus. */
  const focusAfterLoad = useRef(false);
  const rows = useRef(new Map<string, HTMLButtonElement>());
  const note = useRef<HTMLInputElement>(null);
  const items = useApi(
    `review/${status}/${kind ?? ''}/${category ?? ''}/${page}`,
    (s) => api.listReviewItems({ status, kind, category }, page, PAGE_SIZE, s),
    { cache: false },
  );
  const { retry, focusRef: heading } = useRetry(items.reload, items.loading);
  // The discovery categories, for the category filter (ADR 0051); none while the list cannot load.
  const sources = useApi('sources', (s) => api.listSources(s));
  const categories = (sources.data ?? sources.previous ?? []).filter((s) => s.family);
  const list = items.data?.items ?? [];
  const selected = list.find((i) => i.id === selectedId) ?? list[0];
  // Picks live on one page (a new page or filter clears them): once the list has loaded, an item no longer open in it
  // is decided, so it is not picked any more.
  const picked = items.data ? openPicks(pickedIds, list) : pickedIds;

  useEffect(() => {
    if (items.loading || !focusAfterLoad.current) return;
    focusAfterLoad.current = false;
    const row = selected && rows.current.get(selected.id);
    if (row) row.focus();
    else heading.current?.focus();
  }, [items.loading, selected, heading]);

  /** A new filter starts on the first page; the other filter stays. */
  const setFilter = (name: 'status' | 'kind' | 'category' | 'page', value: string | undefined) => {
    const next = new URLSearchParams(params);
    if (value === undefined) next.delete(name);
    else next.set(name, value);
    if (name !== 'page') next.delete('page');
    setParams(next);
    setPicked(new Map());
  };

  const toggle = (item: ReviewItem) => {
    if (item.status !== 'OPEN') return;
    setPicked((p) => {
      const next = new Map(p);
      if (!next.delete(item.id)) next.set(item.id, item);
      return next;
    });
  };

  const select = (id: string | undefined, focus: boolean) => {
    if (!id) return;
    setSelectedId(id);
    if (focus) rows.current.get(id)?.focus();
  };

  /**
   * One action at a time: while a request runs, every action button is aria-disabled (it keeps focus) and a second
   * click does nothing. An item someone else decided first (409) is not an error to retry: the list is reloaded. A
   * refused draft stays open, with the reason.
   */
  const resolve = async (item: ReviewItem, decision: Decision) => {
    if (busy || item.status !== 'OPEN') return;
    setBusy(true);
    setActionError(null);
    try {
      const text = notes[item.id]?.trim();
      await api.resolveReviewItem(item.id, { status: decision, ...(text ? { note: text } : {}) });
      setNotes(({ [item.id]: _, ...rest }) => rest);
      const left = Math.max(0, (items.data?.total ?? 1) - 1);
      setAnnouncement(t(`admin.review.decided.${decision}`, { count: left }));
      setSelectedId(nextAfter(list, item.id));
      focusAfterLoad.current = true;
      items.reload();
    } catch (e) {
      const error = actionErrorOf(e);
      setActionError(error);
      if (error.kind === 'alreadyDecided') items.reload();
    } finally {
      setBusy(false);
    }
  };

  /**
   * Decides the picked items together. The items that failed stay picked, so the reason is easy to check; the list
   * reloads and the selected row takes focus. When the request itself fails, the list reloads too and the picks still open stay.
   */
  const resolveMany = async (decision: Decision, text: string | undefined) => {
    if (busy || picked.size === 0) return;
    setBusy(true);
    setActionError(null);
    try {
      const result = await api.resolveReviewItems({
        ids: [...picked.keys()],
        status: decision,
        ...(text ? { note: text } : {}),
      });
      const failed = new Set(result.items.filter((i) => i.outcome !== 'RESOLVED').map((i) => i.id));
      setPicked((p) => new Map([...p].filter(([id]) => failed.has(id))));
      setBulkResult(bulkSummary(t, result));
      focusAfterLoad.current = true;
      items.reload();
    } catch {
      // The server decides each item on its own, so some may be decided already: reload; the picks still open stay.
      setActionError({ kind: 'failed' });
      items.reload();
    } finally {
      setBusy(false);
    }
  };

  const onKeyDown = (e: KeyboardEvent) => {
    // A held key repeats: one press is one decision, never a run of them.
    if (e.repeat || !isCommandKey(e)) return;
    const at = selected ? list.indexOf(selected) : -1;
    const act: Record<string, () => void> = {
      j: () => select(list[at + 1]?.id, true),
      k: () => select(list[at - 1]?.id, true),
      x: () => selected && toggle(selected),
      a: () => selected && void resolve(selected, 'ACCEPTED'),
      r: () => selected && void resolve(selected, 'REJECTED'),
      n: () => note.current?.focus(),
      '?': () => setHelp(true),
    };
    const action = act[e.key];
    if (!action) return;
    e.preventDefault();
    action();
  };

  const disabled = busy || undefined;
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
      <p role="status" className="sr-only">
        {announcement}
      </p>
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
        {categories.length > 0 && (
          <label>
            {t('admin.categoryFilter')}
            <select
              value={category ?? ''}
              onChange={(e) => setFilter('category', e.target.value || undefined)}
            >
              <option value="">{t('admin.allCategories')}</option>
              {categories.map((c) => (
                <option key={c.id} value={categoryOf(c)}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
        )}
        <button type="button" className="btn" aria-keyshortcuts="?" onClick={() => setHelp(true)}>
          {t('admin.keys.open')}
        </button>
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
      ) : list.length === 0 || !selected ? (
        <AdminEmpty command={command} text={t('admin.queueEmpty')} />
      ) : (
        // Keys reach the queue only from inside it (WCAG 2.1.4); each control names its key (aria-keyshortcuts).
        // eslint-disable-next-line jsx-a11y/no-static-element-interactions
        <div className="h2-admin__review" onKeyDown={onKeyDown}>
          <ScrollBox label={t('admin.reviewQueue')}>
            <table className="v4-table h2-admin__table h2-admin__list" aria-labelledby="rq-h">
              <thead>
                <tr>
                  <th scope="col">
                    <span className="sr-only">{t('admin.bulk.pickColumn')}</span>
                  </th>
                  <th scope="col">{t('admin.col.kind')}</th>
                  <th scope="col">{t('admin.col.resource')}</th>
                  <th scope="col">{t('admin.col.check')}</th>
                  <th scope="col">{t('admin.col.opened')}</th>
                  <th scope="col">{t('admin.col.status')}</th>
                </tr>
              </thead>
              <tbody>
                {list.map((it) => {
                  const current = it.id === selected.id;
                  return (
                    <tr
                      key={it.id}
                      className={current ? 'v4-row h2-admin__row--current' : 'v4-row'}
                    >
                      <td>
                        {it.status === 'OPEN' && (
                          <input
                            type="checkbox"
                            className="h2-admin__pick-box"
                            checked={picked.has(it.id)}
                            aria-label={t('admin.bulk.pick', { reason: it.reason })}
                            aria-keyshortcuts="x"
                            onChange={() => toggle(it)}
                            onFocus={() => setSelectedId(it.id)}
                          />
                        )}
                      </td>
                      <td className="v4-nowrap">{t(`admin.kind.${it.kind}`)}</td>
                      <td>
                        {it.resourceId ? (
                          <Link
                            className="v4-title"
                            to={resourcePath(it.resourceId, it.resourceTitle)}
                          >
                            {it.resourceTitle ?? it.resourceId}
                          </Link>
                        ) : (
                          '—'
                        )}
                      </td>
                      <td className="h2-admin__what">
                        <button
                          type="button"
                          className="h2-admin__pick"
                          aria-current={current ? 'true' : undefined}
                          aria-controls={DETAIL_ID}
                          aria-keyshortcuts="j k"
                          ref={(el) => {
                            if (el) rows.current.set(it.id, el);
                            else rows.current.delete(it.id);
                          }}
                          onClick={() => select(it.id, false)}
                        >
                          {it.reason}
                        </button>
                      </td>
                      <td className="v4-nowrap">
                        {formatDate(it.createdAt, i18n.language, 'INSTANT')}
                      </td>
                      <td>
                        <StatusPill tone={REVIEW_TONE[it.status]}>
                          {t(`admin.reviewStatus.${it.status}`)}
                        </StatusPill>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </ScrollBox>
          <section id={DETAIL_ID} aria-labelledby="rq-detail-h" className="h2-admin__detail">
            <h3 id="rq-detail-h" className="h2-admin__reason">
              {selected.reason}
            </h3>
            <p className="muted">
              {t(`admin.kind.${selected.kind}`)}
              {selected.resourceTitle ? ` · ${selected.resourceTitle}` : ''}
            </p>
            <ItemDetails item={selected} />
            {selected.status === 'OPEN' ? (
              <div className="h2-admin__decide">
                <label className="h2-admin__note">
                  {t('admin.note')}
                  <input
                    ref={note}
                    type="text"
                    maxLength={NOTE_MAX}
                    aria-keyshortcuts="n"
                    value={notes[selected.id] ?? ''}
                    onChange={(e) => setNotes((n) => ({ ...n, [selected.id]: e.target.value }))}
                    onKeyDown={(e) => {
                      if (e.key !== 'Escape') return;
                      e.preventDefault();
                      rows.current.get(selected.id)?.focus();
                    }}
                  />
                </label>
                <div className="h2-admin__actions">
                  <button
                    type="button"
                    className="btn btn-primary"
                    aria-disabled={disabled}
                    aria-keyshortcuts="a"
                    onClick={() => void resolve(selected, 'ACCEPTED')}
                  >
                    {t('admin.accept')}
                    <span className="sr-only"> {selected.reason}</span>
                  </button>
                  <button
                    type="button"
                    className="btn"
                    aria-disabled={disabled}
                    aria-keyshortcuts="r"
                    onClick={() => void resolve(selected, 'REJECTED')}
                  >
                    {t('admin.reject')}
                    <span className="sr-only"> {selected.reason}</span>
                  </button>
                </div>
              </div>
            ) : (
              <p>
                <StatusPill tone={REVIEW_TONE[selected.status]}>
                  {t(`admin.reviewStatus.${selected.status}`)}
                </StatusPill>
              </p>
            )}
            <ReviewEvidencePanel key={selected.id} itemId={selected.id} />
          </section>
        </div>
      )}
      <p role="status" className="h2-admin__bulk-result">
        {bulkResult}
      </p>
      {picked.size > 0 && (
        <BulkBar
          picked={[...picked.values()]}
          busy={busy}
          onDecide={(decision, text) => void resolveMany(decision, text)}
          onClear={() => setPicked(new Map())}
        />
      )}
      {items.data && (
        <Pagination
          page={page}
          size={PAGE_SIZE}
          total={items.data.total}
          onPage={(p) => setFilter('page', String(p))}
        />
      )}
      <ReviewKeysHelp open={help} onClose={() => setHelp(false)} />
    </section>
  );
}
