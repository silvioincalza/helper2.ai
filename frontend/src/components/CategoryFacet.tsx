import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import type { Category, FacetValue } from '@/api/types';
import type { Categories } from '@/hooks/useCategories';
import { isSelected } from '@/lib/searchState';

const NONE = 'unknown';

interface Props {
  /** The search's category counts (Facets.category): every filter applied but the category's own. */
  counts: FacetValue[];
  /** The ids in the URL (`?category=`). */
  selected: string[];
  categories: Categories;
  onToggle: (id: string) => void;
}

/**
 * The Category facet of the Filters panel (ADR 0053): one group per family, in the configured order, each category with
 * the number of results in it, then "No category yet". Only categories with results, or already selected, are listed, so
 * no choice leads to an empty page. Categories are assigned by code from public source data, not by a model, so they
 * carry no AI label.
 */
export function CategoryFacet({ counts, selected, categories, onToggle }: Props) {
  const { t } = useTranslation();
  const idBase = useId();
  const count = (id: string) => counts.find((c) => c.value === id)?.count ?? 0;
  const shown = (c: Category) => count(c.id) > 0 || isSelected(selected, c.id);
  const families = groupByFamily(categories.list.filter(shown));
  const none = count(NONE);
  const noneOn = isSelected(selected, NONE);

  const option = (id: string, label: string, n: number) => {
    const inputId = `${idBase}-${id}`;
    return (
      <li key={id}>
        <input
          id={inputId}
          type="checkbox"
          checked={isSelected(selected, id)}
          onChange={() => onToggle(id)}
        />
        <label htmlFor={inputId}>
          {label} <span className="count">({n})</span>
        </label>
      </li>
    );
  };

  return (
    <fieldset className="filter-group filter-group--category">
      <legend className="filter-legend">{t('facet.category')}</legend>
      {categories.loading ? (
        <p role="status" className="muted small">
          {t('categories.loading')}
        </p>
      ) : categories.error !== undefined ? (
        <p role="alert" className="muted small">
          {t('categories.error')}{' '}
          <button type="button" className="btn-text" onClick={categories.reload}>
            {t('categories.retry')}
          </button>
        </p>
      ) : (
        <>
          {families.length === 0 && <p className="muted small">{t('categories.empty')}</p>}
          {families.map(([family, list]) => (
            <fieldset key={family} className="filter-family">
              <legend className="filter-family__legend">{t(`sourcesPage.family.${family}`)}</legend>
              <ul className="plain-list">{list.map((c) => option(c.id, c.name, count(c.id)))}</ul>
            </fieldset>
          ))}
          {(none > 0 || noneOn) && (
            <ul className="plain-list filter-family__none">
              {option(NONE, t('categories.none'), none)}
            </ul>
          )}
        </>
      )}
    </fieldset>
  );
}

/** The categories grouped by family, families in the order of their first category. */
function groupByFamily(list: Category[]): [Category['family'], Category[]][] {
  const groups = new Map<Category['family'], Category[]>();
  for (const c of list) groups.set(c.family, [...(groups.get(c.family) ?? []), c]);
  return [...groups.entries()];
}
