import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import type { FacetValue, Facets, SourceStatus } from '@/api/types';
import {
  filterValue,
  isSelected,
  patchState,
  toggleFilter,
  type MultiKey,
  type SearchState,
} from '@/lib/searchState';
import type { Categories } from '@/hooks/useCategories';
import { CategoryFacet } from './CategoryFacet';
import { Term, type GlossaryKey } from '../../ui/Term';

type FacetKey = Exclude<MultiKey, 'category'> | 'openSource';

const ORDER: FacetKey[] = [
  'type',
  'useCase',
  'pricing',
  'license',
  'openSource',
  'source',
  'platform',
  'techLevel',
];
const TERMS: Partial<Record<FacetKey, GlossaryKey>> = {
  license: 'license',
  openSource: 'open_source',
  type: 'model',
  techLevel: 'tech_level',
  platform: 'platform',
};

interface Props {
  facets: Facets | undefined;
  state: SearchState;
  sources: SourceStatus[];
  categories: Categories;
  onChange: (s: SearchState) => void;
}

export function FilterFacets({ facets, state, sources, categories, onChange }: Props) {
  const { t } = useTranslation();
  const idBase = useId();

  const label = (key: FacetKey, value: string): string => {
    if (value.toLowerCase() === 'unknown') return t('common.unknown');
    switch (key) {
      case 'source':
        return sources.find((s) => s.sourceId === value)?.name ?? value;
      case 'type':
        return t(`type.${value}`, { defaultValue: value });
      case 'pricing':
        return t(`pricing.${value}`, { defaultValue: value });
      case 'techLevel':
        return t(`techLevel.${value}`, { defaultValue: value });
      case 'useCase':
        return t(`useCase.${value}`, { defaultValue: value });
      case 'openSource':
        return value === 'true' ? t('common.yes') : value === 'false' ? t('common.no') : value;
      default:
        return value;
    }
  };

  const values = (key: FacetKey): FacetValue[] => {
    const base = facets?.[key] ?? [];
    const selected =
      key === 'openSource' ? (state.openSource ? [state.openSource] : []) : state.filters[key];
    const extra = selected
      .filter((s) => !base.some((b) => b.value.toLowerCase() === s.toLowerCase()))
      .map((s) => ({ value: s, count: 0 }));
    return [...base, ...extra];
  };

  const isOn = (key: FacetKey, value: string) =>
    key === 'openSource'
      ? state.openSource.toLowerCase() === value.toLowerCase()
      : isSelected(state.filters[key], value);

  const toggle = (key: FacetKey, value: string) => {
    if (key === 'openSource') {
      const v = value.toLowerCase();
      onChange(
        patchState(state, {
          openSource: state.openSource === v ? '' : (v as 'true' | 'false' | 'unknown'),
        }),
      );
    } else {
      onChange(toggleFilter(state, key, filterValue(key, value)));
    }
  };

  return (
    <aside className="filters" aria-label={t('filters.title')}>
      <div className="filter-groups">
        <div className="filter-group">
          <label htmlFor="updatedAfter" className="filter-legend">
            {t('filters.updatedAfter')}
          </label>
          <input
            id="updatedAfter"
            type="date"
            value={state.updatedAfter}
            onChange={(e) => onChange(patchState(state, { updatedAfter: e.target.value }))}
          />
          <p className="muted small">{t('filters.updatedAfterHint')}</p>
        </div>
        {facets && (
          <CategoryFacet
            counts={facets.category}
            selected={state.filters.category}
            categories={categories}
            onToggle={(id) => onChange(toggleFilter(state, 'category', id))}
          />
        )}
        {ORDER.map((key) => {
          const list = values(key);
          if (list.length === 0) return null;
          const term = TERMS[key];
          return (
            <fieldset key={key} className="filter-group">
              <legend className="filter-legend">
                {term && key !== 'type' ? (
                  <Term term={term}>{t(`facet.${key}`)}</Term>
                ) : (
                  t(`facet.${key}`)
                )}
              </legend>
              <ul className="plain-list">
                {list.map((f, i) => {
                  const id = `${idBase}-${key}-${i}`;
                  return (
                    <li key={f.value}>
                      <input
                        id={id}
                        type="checkbox"
                        checked={isOn(key, f.value)}
                        onChange={() => toggle(key, f.value)}
                      />
                      <label htmlFor={id}>
                        {label(key, f.value)} <span className="count">({f.count})</span>
                      </label>
                    </li>
                  );
                })}
              </ul>
            </fieldset>
          );
        })}
      </div>
    </aside>
  );
}
