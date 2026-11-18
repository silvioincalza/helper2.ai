import './adminText';
import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from 'react';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import { api } from '@/api/client';
import type { ReviewItem } from '@/api/types';
import { Icon } from '@/ui/Icon';
import { Pagination } from '@/admin/Pagination';
import { RetryButton, StatePanel } from '@/ui/StatePanel';
import { ScrollBox } from '@/ui/ScrollBox';
import { StatusPill } from '@/ui/StatusPill';
import { useApi } from '@/hooks/useApi';
import { useRetry } from '@/hooks/useRetry';
import { formatDate } from '@/lib/format';
import { resourcePath } from '@/lib/searchState';
import { categoryOf } from '@/features/sources/DiscoveryCategories';
import { AdminEmpty, AdminHead } from './AdminHead';
import { TableSkeleton } from '@/ui/Skeleton';
import { REVIEW_KINDS, REVIEW_STATUSES, REVIEW_TONE } from './adminModel';
import { BulkBar } from './ReviewBulk';
import { ReviewEvidencePanel } from './ReviewEvidence';
import { ItemDetails, parseCandidate, type Candidate } from './ReviewItemDetails';
import { ReviewKeysHelp } from './ReviewKeysHelp';
import { RelationshipGroups } from './RelationshipGroups';
import { NOTE_MAX } from './limits';
import {
  BatchRejectDialog,
  RESEARCH_FILTERS,
  ResearchBar,
  ResearchCell,
  ResearchRunLine,
  StartAllDialog,
  hasResearch,
  researchable,
  useResearchRuns,
} from './ReviewResearch';
import { ResearchDecisions, type ResearchOutcome } from './ResearchDecisions';
import { useBarHeight } from './useBarHeight';
import { useReviewActions } from './useReviewActions';
import { useReviewFilters } from './useReviewFilters';
import { useReviewSelection } from './useReviewSelection';

const PAGE_SIZE = 50;
const DETAIL_ID = 'rq-detail';

function isCommandKey(e: KeyboardEvent): boolean {
  const target = e.target as HTMLElement;
  if (target.closest('.h2-admin__research')) return false;
  const box = target instanceof HTMLInputElement && target.type === 'checkbox';
  const typing =
    target.isContentEditable || (!box && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName));
  return !typing && !e.ctrlKey && !e.metaKey && !e.altKey;
}

const RELATIONSHIP = /^Unverified relationship: ([A-Z_]+) (.+)$/;

function rowText(
  t: TFunction,
  item: ReviewItem,
  reason: string,
  candidate: Candidate | null,
): string {
  if (candidate) {
    const type = t(`type.${candidate.type}`, { defaultValue: candidate.type });
    return [type, ...candidate.tags, ...candidate.foundBy].join(' · ');
  }
  const relationship = item.kind === 'RELATIONSHIP_CANDIDATE' ? RELATIONSHIP.exec(reason) : null;
  if (relationship) {
    const [, kind, target] = relationship;
    return t(`admin.review.row.relationship.${kind}`, { target, defaultValue: reason });
  }
  return reason;
}

function nextAfter(list: ReviewItem[], id: string): string | undefined {
  const at = list.findIndex((i) => i.id === id);
  return (list[at + 1] ?? list[at - 1])?.id;
}

function useRetoldReasons(page: unknown) {
  const [retold, setRetold] = useState<{ of: unknown; reasons: Record<string, string> }>({
    of: undefined,
    reasons: {},
  });
  const reasons = retold.of === page ? retold.reasons : {};
  const retell = useCallback(
    (id: string, reason: string) =>
      setRetold((r) => {
        const base = r.of === page ? r.reasons : {};
        return r.of === page && base[id] === reason
          ? r
          : { of: page, reasons: { ...base, [id]: reason } };
      }),
    [page],
  );
  return { reasons, retell };
}

export default function ReviewQueuePage() {
  const { t } = useTranslation();
  const filters = useReviewFilters();
  const { status, kind, category, page, research: researchFilter, byTarget } = filters;
  const [selectedId, setSelectedId] = useState<string | undefined>();
  const [help, setHelp] = useState(false);
  const [outcome, setOutcome] = useState<ResearchOutcome | null>(null);
  const focusAfterLoad = useRef(false);
  const rows = useRef(new Map<string, HTMLButtonElement>());
  const note = useRef<HTMLInputElement>(null);
  const accept = useRef<HTMLButtonElement>(null);
  const decideBar = useRef<HTMLDivElement>(null);
  const detailBox = useRef<HTMLElement>(null);
  const researchSelect = useRef<HTMLSelectElement>(null);
  const focusAccept = useCallback(() => accept.current?.focus(), []);
  const items = useApi(
    `review/${status}/${kind ?? ''}/${category ?? ''}/${researchFilter ?? ''}/${page}`,
    (s) =>
      adminApi.listReviewItems(
        { status, kind, category, research: researchFilter },
        page,
        PAGE_SIZE,
        s,
      ),
    { cache: false },
  );
  const { retry, focusRef: heading } = useRetry(items.reload, items.loading);
  const sources = useApi('sources', (s) => api.listSources(s));
  const overview = useApi('admin-overview', (s) => adminApi.getAdminOverview(s), { cache: false });
  const reloadQueue = () => {
    items.reload();
    overview.reload();
  };
  const openCounts = new Map(
    (overview.data?.openReviewItems ?? []).map(({ kind: k, count }) => [k, count] as const),
  );
  const categories = (sources.data ?? sources.previous ?? []).filter((s) => s.family);
  const loaded = items.data?.items ?? [];
  const research = useResearchRuns(loaded.filter(hasResearch).map((i) => i.id));
  const { reasons, retell } = useRetoldReasons(items.data);
  const selected = loaded.find((i) => i.id === selectedId) ?? loaded[0];
  const reason = selected ? (reasons[selected.id] ?? selected.reason) : '';
  const selection = useReviewSelection(items.data ? loaded : undefined);
  const { picked, toggle } = selection;
  const researched = selected ? outcome : null;
  const selectedRun = selected ? research.runs.get(selected.id) : undefined;
  const actions = useReviewActions({
    total: items.data?.total,
    reloadQueue,
    reloadResearch: research.reload,
    researched,
    pickedIds: () => [...picked.keys()],
    keepFailedPicks: selection.keepFailed,
    onResolved: (item) => {
      setSelectedId(nextAfter(loaded, item.id));
      focusAfterLoad.current = true;
    },
    onResolvedMany: () => {
      focusAfterLoad.current = true;
    },
  });
  const { busy, resolve, resolveMany, startResearch, cancelResearch } = actions;
  const blocked = actions.acceptBlocked;

  useEffect(() => {
    if (items.loading || !focusAfterLoad.current) return;
    focusAfterLoad.current = false;
    const row = selected && rows.current.get(selected.id);
    if (row) row.focus();
    else heading.current?.focus();
  }, [items.loading, selected, heading]);

  useBarHeight(
    decideBar,
    detailBox,
    '--decide-h',
    `${selected?.id}/${selected?.status}/${items.loading}`,
  );

  const setFilter: typeof filters.setFilter = (name, value) => {
    filters.setFilter(name, value);
    selection.clear();
  };

  const select = (id: string | undefined, focus: boolean) => {
    if (!id) return;
    setSelectedId(id);
    if (focus) rows.current.get(id)?.focus();
  };

  const onKeyDown = (e: KeyboardEvent) => {
    if (e.repeat || !isCommandKey(e)) return;
    const at = selected ? loaded.indexOf(selected) : -1;
    const act: Record<string, () => void> = {
      j: () => select(loaded[at + 1]?.id, true),
      k: () => select(loaded[at - 1]?.id, true),
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
      {actions.error && (
        <p role="alert" className="banner banner-warn">
          <Icon name="warn" />{' '}
          {actions.error.kind === 'refused'
            ? t('admin.refused', { detail: actions.error.detail })
            : t(
                actions.error.kind === 'alreadyDecided'
                  ? 'admin.alreadyDecided'
                  : 'admin.actionFailed',
              )}
        </p>
      )}
      {actions.listed && (
        <p role="status" className="banner">
          <Icon name="info" /> {t('admin.review.alreadyListed', { title: actions.listed.title })}{' '}
          <a href={resourcePath(actions.listed.resourceId, actions.listed.title)}>
            {t('admin.review.openListed')}
          </a>
        </p>
      )}
      <p role="status" className="sr-only">
        {actions.announcement}
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
            {REVIEW_KINDS.map((k) => {
              const count = status === 'OPEN' ? openCounts.get(k) : undefined;
              return (
                <option key={k} value={k}>
                  {count === undefined
                    ? t(`admin.kind.${k}`)
                    : t('admin.kindCount', { kind: t(`admin.kind.${k}`), count })}
                </option>
              );
            })}
          </select>
        </label>
        {categories.length > 0 && !byTarget && (
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
        {status === 'OPEN' && kind === 'RELATIONSHIP_CANDIDATE' && (
          <span className="h2-admin__views" role="group" aria-label={t('admin.groups.view.label')}>
            <button
              type="button"
              className="btn"
              aria-pressed={byTarget}
              onClick={() => setFilter('view', undefined)}
            >
              {t('admin.groups.view.groups')}
            </button>
            <button
              type="button"
              className="btn"
              aria-pressed={!byTarget}
              onClick={() => setFilter('view', 'items')}
            >
              {t('admin.groups.view.items')}
            </button>
          </span>
        )}
        {!byTarget && (
          <label>
            {t('admin.research.filter')}
            <select
              ref={researchSelect}
              value={researchFilter ?? ''}
              onChange={(e) => setFilter('research', e.target.value || undefined)}
            >
              <option value="">{t('admin.research.filterAny')}</option>
              {RESEARCH_FILTERS.map((f) => (
                <option key={f} value={f}>
                  {t(`admin.research.filterOption.${f}`)}
                </option>
              ))}
            </select>
          </label>
        )}
        <button type="button" className="btn" aria-keyshortcuts="?" onClick={() => setHelp(true)}>
          {t('admin.keys.open')}
        </button>
      </div>
      {!byTarget && status === 'OPEN' && loaded.some(researchable) && (
        <ResearchBar
          picked={[...picked.values()]}
          page={loaded}
          runs={research.runs}
          busy={busy}
          onStart={(itemIds) => void startResearch({ itemIds, allOpen: false })}
          onStartAll={() => actions.setStartingAll(true)}
          onRejectResearched={actions.setRejecting}
        />
      )}
      {byTarget ? (
        <RelationshipGroups
          page={page}
          onPage={(p) => setFilter('page', String(p))}
          onDecided={overview.reload}
        />
      ) : items.loading ? (
        <TableSkeleton />
      ) : items.error !== undefined ? (
        <StatePanel
          tone="error"
          level={3}
          title={t('admin.loadError')}
          next={<RetryButton onClick={retry} />}
        />
      ) : loaded.length === 0 || !selected ? (
        researchFilter ? (
          <AdminEmpty
            command={command}
            text={t('admin.research.filterEmpty')}
            next={
              <button
                type="button"
                className="btn btn-sm"
                onClick={() => {
                  setFilter('research', undefined);
                  researchSelect.current?.focus();
                }}
              >
                {t('admin.research.filterClear')}
              </button>
            }
          />
        ) : (
          <AdminEmpty command={command} text={t('admin.queueEmpty')} />
        )
      ) : (
        // eslint-disable-next-line jsx-a11y/no-static-element-interactions
        <div className="h2-admin__review" onKeyDown={onKeyDown}>
          <ScrollBox label={t('admin.reviewQueue')}>
            <table className="h2-table h2-admin__table h2-admin__list" aria-labelledby="rq-h">
              <thead>
                <tr>
                  <th scope="col">
                    <span className="sr-only">{t('admin.bulk.pickColumn')}</span>
                  </th>
                  <th scope="col">{t('admin.col.kind')}</th>
                  <th scope="col">{t('admin.col.resource')}</th>
                  <th scope="col">{t('admin.col.check')}</th>
                  <th scope="col">{t('admin.research.col.research')}</th>
                  <th scope="col">{t('admin.col.opened')}</th>
                  <th scope="col">{t('admin.col.status')}</th>
                </tr>
              </thead>
              <tbody>
                {loaded.map((it) => {
                  const current = it.id === selected.id;
                  const candidate =
                    it.kind === 'RESOURCE_CANDIDATE' ? parseCandidate(it.details) : null;
                  return (
                    <tr
                      key={it.id}
                      className={current ? 'h2-term-row h2-admin__row--current' : 'h2-term-row'}
                    >
                      <td>
                        {it.status === 'OPEN' && (
                          <input
                            type="checkbox"
                            className="h2-admin__pick-box"
                            checked={picked.has(it.id)}
                            aria-label={t('admin.bulk.pick', {
                              reason: reasons[it.id] ?? it.reason,
                            })}
                            aria-keyshortcuts="x"
                            onChange={() => toggle(it)}
                            onFocus={() => setSelectedId(it.id)}
                          />
                        )}
                      </td>
                      <td className="h2-nowrap">{t(`admin.kind.${it.kind}`)}</td>
                      <td className="h2-admin__resource">
                        {it.resourceId ? (
                          <a
                            className="h2-title"
                            href={resourcePath(it.resourceId, it.resourceTitle)}
                          >
                            {it.resourceTitle ?? it.resourceId}
                          </a>
                        ) : candidate ? (
                          <a
                            className="h2-title"
                            href={candidate.url}
                            target="_blank"
                            rel="noopener noreferrer nofollow"
                          >
                            {candidate.title}
                          </a>
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
                          {rowText(t, it, reasons[it.id] ?? it.reason, candidate)}
                        </button>
                      </td>
                      <td>
                        <ResearchCell
                          item={it}
                          run={research.runs.get(it.id)}
                          busy={busy}
                          onStart={(id) => void startResearch({ itemIds: [id], allOpen: false })}
                          onCancel={(run) => void cancelResearch(run.id)}
                        />
                      </td>
                      <td className="h2-nowrap">{formatDate(it.createdAt, 'INSTANT')}</td>
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
          <section
            key={selected.id}
            ref={detailBox}
            id={DETAIL_ID}
            aria-labelledby="rq-detail-h"
            className="h2-admin__detail"
          >
            <h3 id="rq-detail-h" className="h2-admin__reason">
              {reason}
            </h3>
            <p className="muted">
              {t(`admin.kind.${selected.kind}`)}
              {selected.resourceTitle ? ` · ${selected.resourceTitle}` : ''}
            </p>
            <ItemDetails item={selected} onChanged={items.reload} />
            {selectedRun?.status === 'DONE' && researchable(selected) && (
              <ResearchDecisions
                key={selectedRun.id}
                run={selectedRun}
                onOutcome={setOutcome}
                onDone={focusAccept}
              />
            )}
            {selectedRun && !(selectedRun.status === 'DONE' && researchable(selected)) && (
              <ResearchRunLine run={selectedRun} />
            )}
            {selected.status === 'OPEN' ? (
              <div ref={decideBar} className="h2-admin__decide">
                <label className="h2-admin__note">
                  {t('admin.note')}
                  <input
                    ref={note}
                    type="text"
                    maxLength={NOTE_MAX}
                    aria-keyshortcuts="n"
                    value={actions.noteOf(selected.id)}
                    onChange={(e) => actions.setNote(selected.id, e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key !== 'Escape') return;
                      e.preventDefault();
                      rows.current.get(selected.id)?.focus();
                    }}
                  />
                </label>
                <div className="h2-admin__actions">
                  <button
                    ref={accept}
                    type="button"
                    className="btn btn-primary"
                    aria-disabled={disabled || blocked || undefined}
                    aria-describedby={blocked ? 'rq-accept-why' : undefined}
                    aria-keyshortcuts="a"
                    onClick={() => void resolve(selected, 'ACCEPTED')}
                  >
                    {t(researched ? 'admin.research.accept' : 'admin.accept')}
                    <span className="sr-only"> {reason}</span>
                  </button>
                  <button
                    type="button"
                    className="btn"
                    aria-disabled={disabled}
                    aria-keyshortcuts="r"
                    onClick={() => void resolve(selected, 'REJECTED')}
                  >
                    {t('admin.reject')}
                    <span className="sr-only"> {reason}</span>
                  </button>
                  {blocked && researched && (
                    <span id="rq-accept-why" className="h2-admin__research-why">
                      {t('admin.research.acceptWhy', { count: researched.left.length })}
                    </span>
                  )}
                </div>
              </div>
            ) : (
              <p>
                <StatusPill tone={REVIEW_TONE[selected.status]}>
                  {t(`admin.reviewStatus.${selected.status}`)}
                </StatusPill>
              </p>
            )}
            <ReviewEvidencePanel itemId={selected.id} onReason={retell} />
          </section>
        </div>
      )}
      <p role="status" className="h2-admin__bulk-result">
        {actions.bulkResult}
      </p>
      {picked.size > 0 && (
        <BulkBar
          picked={[...picked.values()]}
          busy={busy}
          onDecide={(decision, text) => void resolveMany(decision, text)}
          onClear={selection.clear}
        />
      )}
      {items.data && !byTarget && (
        <Pagination
          page={page}
          size={PAGE_SIZE}
          total={items.data.total}
          onPage={(p) => setFilter('page', String(p))}
        />
      )}
      <ReviewKeysHelp open={help} onClose={() => setHelp(false)} />
      <StartAllDialog
        open={actions.startingAll}
        busy={busy}
        onClose={() => actions.setStartingAll(false)}
        onConfirm={() => void startResearch({ allOpen: true })}
      />
      <BatchRejectDialog
        items={actions.rejecting}
        runs={research.runs}
        busy={busy}
        onClose={() => actions.setRejecting([])}
        onConfirm={(ids, text) => void resolveMany('REJECTED', text, ids)}
      />
    </section>
  );
}
