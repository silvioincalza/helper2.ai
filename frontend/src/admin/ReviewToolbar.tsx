import type { Ref } from 'react';
import { useTranslation } from 'react-i18next';
import type { ReviewKind, SourceInfo } from '@/api/types';
import { categoryOf } from '@/ui/DiscoveryCategories';
import { REVIEW_KINDS, REVIEW_STATUSES } from './adminModel';
import { RESEARCH_FILTERS } from './ReviewResearch';
import type { ReviewFilters, useReviewFilters } from './useReviewFilters';

export function ReviewToolbar({
  filters,
  openCounts,
  categories,
  researchSelectRef,
  onFilter,
  onHelp,
}: {
  filters: ReviewFilters;
  openCounts: ReadonlyMap<ReviewKind, number>;
  categories: SourceInfo[];
  researchSelectRef: Ref<HTMLSelectElement>;
  onFilter: ReturnType<typeof useReviewFilters>['setFilter'];
  onHelp: () => void;
}) {
  const { t } = useTranslation();
  const { status, kind, category, research, byTarget } = filters;
  return (
    <div className="h2-admin__tools">
      <label>
        {t('admin.status')}
        <select value={status} onChange={(e) => onFilter('status', e.target.value)}>
          {REVIEW_STATUSES.map((s) => (
            <option key={s} value={s}>
              {t(`admin.reviewStatus.${s}`)}
            </option>
          ))}
        </select>
      </label>
      <label>
        {t('admin.kindFilter')}
        <select value={kind ?? ''} onChange={(e) => onFilter('kind', e.target.value || undefined)}>
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
            onChange={(e) => onFilter('category', e.target.value || undefined)}
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
            onClick={() => onFilter('view', undefined)}
          >
            {t('admin.groups.view.groups')}
          </button>
          <button
            type="button"
            className="btn"
            aria-pressed={!byTarget}
            onClick={() => onFilter('view', 'items')}
          >
            {t('admin.groups.view.items')}
          </button>
        </span>
      )}
      {!byTarget && (
        <label>
          {t('admin.research.filter')}
          <select
            ref={researchSelectRef}
            value={research ?? ''}
            onChange={(e) => onFilter('research', e.target.value || undefined)}
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
      <button type="button" className="btn" aria-keyshortcuts="?" onClick={onHelp}>
        {t('admin.keys.open')}
      </button>
    </div>
  );
}
