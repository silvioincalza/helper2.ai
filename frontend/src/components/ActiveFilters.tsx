import { useTranslation } from 'react-i18next';
import { clearFilters, MULTI_KEYS, patchState, toggleFilter, activeFilterCount, type MultiKey, type SearchState } from '@/lib/searchState';
import { formatDate } from '@/lib/format';
import type { SourceStatus } from '@/api/types';

export function ActiveFilters({ state, sources, onChange }: { state: SearchState; sources: SourceStatus[]; onChange: (s: SearchState) => void }) {
  const { t, i18n } = useTranslation();
  if (activeFilterCount(state) === 0) return null;

  const valueLabel = (key: MultiKey, v: string) => {
    if (v.toLowerCase() === 'unknown') return t('common.unknown');
    if (key === 'source') return sources.find((s) => s.sourceId === v)?.name ?? v;
    return t(`${key === 'useCase' ? 'useCase' : key}.${v}`, { defaultValue: v });
  };

  const chips: { id: string; text: string; remove: () => void }[] = [];
  for (const key of MULTI_KEYS) {
    for (const v of state.filters[key]) {
      chips.push({ id: `${key}:${v}`, text: `${t(`facet.${key}`)}: ${valueLabel(key, v)}`, remove: () => onChange(toggleFilter(state, key, v)) });
    }
  }
  if (state.openSource) {
    const v = state.openSource === 'true' ? t('common.yes') : state.openSource === 'false' ? t('common.no') : t('common.unknown');
    chips.push({ id: 'openSource', text: `${t('facet.openSource')}: ${v}`, remove: () => onChange(patchState(state, { openSource: '' })) });
  }
  if (state.updatedAfter) {
    chips.push({
      id: 'updatedAfter',
      text: `${t('filters.updatedAfter')}: ${formatDate(state.updatedAfter, i18n.language)}`,
      remove: () => onChange(patchState(state, { updatedAfter: '' })),
    });
  }

  return (
    <div className="chips" role="group" aria-label={t('filters.active')}>
      <ul className="plain-list inline-list">
        {chips.map((c) => (
          <li key={c.id}>
            <button type="button" className="chip" onClick={c.remove} aria-label={t('filters.remove', { filter: c.text })}>
              {c.text} <span aria-hidden="true">×</span>
            </button>
          </li>
        ))}
      </ul>
      <button type="button" className="btn btn-link" onClick={() => onChange(clearFilters(state))}>{t('filters.clearAll')}</button>
    </div>
  );
}
