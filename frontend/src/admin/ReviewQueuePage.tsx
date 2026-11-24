import './adminText';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { adminApi } from '@/api/admin';
import { api } from '@/api/client';
import type { ReviewItem } from '@/api/types';
import { Pagination } from './Pagination';
import { RetryButton, StatePanel } from '@/ui/StatePanel';
import { useApi } from '@/hooks/useApi';
import { useRetry } from '@/hooks/useRetry';
import { AdminHead } from './AdminHead';
import { TableSkeleton } from '@/ui/Skeleton';
import { BulkBar } from './ReviewBulk';
import { ReviewDetail } from './ReviewDetail';
import { ReviewKeysHelp } from './ReviewKeysHelp';
import { ReviewNotices } from './ReviewNotices';
import { ReviewQueueEmpty } from './ReviewQueueEmpty';
import { RelationshipGroups } from './RelationshipGroups';
import {
  BatchRejectDialog,
  ResearchBar,
  StartAllDialog,
  hasResearch,
  researchable,
  useResearchRuns,
} from './ReviewResearch';
import type { ResearchOutcome } from './ResearchDecisions';
import { ReviewTable } from './ReviewTable';
import { ReviewToolbar } from './ReviewToolbar';
import { useRetoldReasons } from './useRetoldReasons';
import { useReviewActions } from './useReviewActions';
import { useReviewFilters } from './useReviewFilters';
import { commandKeys, useReviewFocus } from './useReviewFocus';
import { useReviewSelection } from './useReviewSelection';

const PAGE_SIZE = 50;

function nextAfter(list: ReviewItem[], id: string): string | undefined {
  const at = list.findIndex((i) => i.id === id);
  return (list[at + 1] ?? list[at - 1])?.id;
}

export default function ReviewQueuePage() {
  const { t } = useTranslation();
  const filters = useReviewFilters();
  const { status, kind, category, page, research: researchFilter, byTarget } = filters;
  const [help, setHelp] = useState(false);
  const [outcome, setOutcome] = useState<ResearchOutcome | null>(null);
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
  const selection = useReviewSelection(items.data ? loaded : undefined);
  const { picked, toggle } = selection;
  const focus = useReviewFocus(loaded, items.loading, heading);
  const { selected, refs } = focus;
  const reason = selected ? (reasons[selected.id] ?? selected.reason) : '';
  const researched = selected ? outcome : null;
  const actions = useReviewActions({
    total: items.data?.total,
    reloadQueue,
    reloadResearch: research.reload,
    researched,
    pickedIds: () => [...picked.keys()],
    keepFailedPicks: selection.keepFailed,
    onResolved: (item) => {
      focus.show(nextAfter(loaded, item.id));
      focus.focusAfterNextLoad();
    },
    onResolvedMany: focus.focusAfterNextLoad,
  });
  const { busy, resolve, resolveMany, startResearch } = actions;

  const setFilter: typeof filters.setFilter = (name, value) => {
    filters.setFilter(name, value);
    selection.clear();
  };
  const clearResearchFilter = () => {
    setFilter('research', undefined);
    focus.focusResearchFilter();
  };
  const researchItems = (itemIds: string[]) => void startResearch({ itemIds, allOpen: false });

  const onKeyDown = commandKeys({
    j: () => focus.move(1),
    k: () => focus.move(-1),
    x: () => selected && toggle(selected),
    a: () => selected && void resolve(selected, 'ACCEPTED'),
    r: () => selected && void resolve(selected, 'REJECTED'),
    n: focus.focusNote,
    '?': () => setHelp(true),
  });

  const command = `searchavo review --${status.toLowerCase()}`;

  return (
    <section aria-labelledby="rq-h" className="h2-admin__part">
      <AdminHead
        id="rq-h"
        title={t('admin.reviewQueue')}
        command={command}
        total={items.loading ? null : (items.data?.total ?? null)}
        headingRef={heading}
      />
      <ReviewNotices
        error={actions.error}
        listed={actions.listed}
        announcement={actions.announcement}
      />
      <ReviewToolbar
        filters={filters}
        openCounts={openCounts}
        categories={categories}
        researchSelectRef={refs.researchSelect}
        onFilter={setFilter}
        onHelp={() => setHelp(true)}
      />
      {!byTarget && status === 'OPEN' && loaded.some(researchable) && (
        <ResearchBar
          picked={[...picked.values()]}
          page={loaded}
          runs={research.runs}
          busy={busy}
          onStart={researchItems}
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
        <ReviewQueueEmpty
          command={command}
          onClearResearchFilter={researchFilter && clearResearchFilter}
        />
      ) : (
        // eslint-disable-next-line jsx-a11y/no-static-element-interactions
        <div className="h2-admin__review" onKeyDown={onKeyDown}>
          <ReviewTable
            items={loaded}
            selected={selected}
            picked={picked}
            reasons={reasons}
            runs={research.runs}
            busy={busy}
            onRow={focus.setRow}
            onPick={toggle}
            onShow={focus.show}
            onSelect={(id) => focus.select(id, false)}
            onResearch={researchItems}
            onCancelResearch={(run) => void actions.cancelResearch(run.id)}
          />
          <ReviewDetail
            key={selected.id}
            item={selected}
            reason={reason}
            run={research.runs.get(selected.id)}
            researched={researched}
            blocked={actions.acceptBlocked}
            busy={busy}
            note={actions.noteOf(selected.id)}
            boxRef={refs.detailBox}
            barRef={refs.decideBar}
            noteRef={refs.note}
            acceptRef={refs.accept}
            onChanged={items.reload}
            onOutcome={setOutcome}
            onResearchDone={focus.focusAccept}
            onReason={retell}
            onNote={(text) => actions.setNote(selected.id, text)}
            onLeaveNote={() => focus.focusRow(selected.id)}
            onDecide={(decision) => void resolve(selected, decision)}
          />
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
