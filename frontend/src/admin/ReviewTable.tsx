import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import type { ReviewCandidateDetails, ReviewItem, ReviewResearchRun } from '@/api/types';
import { formatDate, safeHref } from '@/lib/format';
import { resourcePath } from '@/lib/searchState';
import { ScrollBox } from '@/ui/ScrollBox';
import { StatusPill } from '@/ui/StatusPill';
import { REVIEW_TONE } from './adminModel';
import { DETAIL_ID } from './ReviewDetail';
import { candidateOf } from './ReviewItemDetails';
import { ResearchCell } from './ReviewResearch';
import type { Picks } from './useReviewSelection';

const RELATIONSHIP = /^Unverified relationship: ([A-Z_]+) (.+)$/;

function rowText(
  t: TFunction,
  item: ReviewItem,
  reason: string,
  candidate: ReviewCandidateDetails | null,
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

interface RowActions {
  onRow: (id: string, row: HTMLButtonElement | null) => void;
  onPick: (item: ReviewItem) => void;
  onShow: (id: string) => void;
  onSelect: (id: string) => void;
  onResearch: (itemIds: string[]) => void;
  onCancelResearch: (run: ReviewResearchRun) => void;
}

export function ReviewTable({
  items,
  selected,
  picked,
  reasons,
  runs,
  busy,
  ...actions
}: RowActions & {
  items: ReviewItem[];
  selected: ReviewItem;
  picked: Picks;
  reasons: Record<string, string>;
  runs: ReadonlyMap<string, ReviewResearchRun>;
  busy: boolean;
}) {
  const { t } = useTranslation();
  return (
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
          {items.map((it) => (
            <ReviewRow
              key={it.id}
              item={it}
              current={it.id === selected.id}
              reason={reasons[it.id] ?? it.reason}
              picked={picked.has(it.id)}
              run={runs.get(it.id)}
              busy={busy}
              {...actions}
            />
          ))}
        </tbody>
      </table>
    </ScrollBox>
  );
}

function ReviewRow({
  item,
  current,
  reason,
  picked,
  run,
  busy,
  onRow,
  onPick,
  onShow,
  onSelect,
  onResearch,
  onCancelResearch,
}: RowActions & {
  item: ReviewItem;
  current: boolean;
  reason: string;
  picked: boolean;
  run: ReviewResearchRun | undefined;
  busy: boolean;
}) {
  const { t } = useTranslation();
  const candidate = item.kind === 'RESOURCE_CANDIDATE' ? candidateOf(item) : null;
  return (
    <tr className={current ? 'h2-term-row h2-admin__row--current' : 'h2-term-row'}>
      <td>
        {item.status === 'OPEN' && (
          <input
            type="checkbox"
            className="h2-admin__pick-box"
            checked={picked}
            aria-label={t('admin.bulk.pick', { reason })}
            aria-keyshortcuts="x"
            onChange={() => onPick(item)}
            onFocus={() => onShow(item.id)}
          />
        )}
      </td>
      <td className="h2-nowrap">{t(`admin.kind.${item.kind}`)}</td>
      <td className="h2-admin__resource">
        {item.resourceId ? (
          <a className="h2-title" href={resourcePath(item.resourceId, item.resourceTitle)}>
            {item.resourceTitle ?? item.resourceId}
          </a>
        ) : candidate ? (
          <a
            className="h2-title"
            href={safeHref(candidate.url)}
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
          ref={(row) => onRow(item.id, row)}
          onClick={() => onSelect(item.id)}
        >
          {rowText(t, item, reason, candidate)}
        </button>
      </td>
      <td>
        <ResearchCell
          item={item}
          run={run}
          busy={busy}
          onStart={(id) => onResearch([id])}
          onCancel={onCancelResearch}
        />
      </td>
      <td className="h2-nowrap">{formatDate(item.createdAt, 'INSTANT')}</td>
      <td>
        <StatusPill tone={REVIEW_TONE[item.status]}>
          {t(`admin.reviewStatus.${item.status}`)}
        </StatusPill>
      </td>
    </tr>
  );
}
