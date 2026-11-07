import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { useCategories } from '@/hooks/useCategories';
import { homeParams, parseSearchParams, patchState, RESULTS_PATH } from '@/lib/searchState';

/** The search for one category: its name as the words, filtered to the category. */
function categorySearch(id: string, name: string): string {
  const empty = parseSearchParams(new URLSearchParams());
  const state = patchState(empty, { q: name, filters: { ...empty.filters, category: [id] } });
  return `${RESULTS_PATH}?${homeParams(state)}`;
}

/**
 * A resource's categories (ADR 0013), each a link to the search filtered by it. Assigned by code from public source data,
 * so no AI label. Nothing is shown when it has none, or while the names are not loaded (the ids alone mean little).
 */
export function CategoryLinks({ ids }: { ids: string[] }) {
  const { t } = useTranslation();
  const { byId } = useCategories();
  const known = ids.flatMap((id) => {
    const c = byId.get(id);
    return c ? [c] : [];
  });
  if (known.length === 0) return null;
  return (
    <section className="h2-res__sec" aria-labelledby="h2-res-categories">
      <h2 id="h2-res-categories" className="h2-res__h">
        {t('categories.title')}
      </h2>
      <ul className="plain-list inline-list h2-res__categories">
        {known.map((c) => (
          <li key={c.id}>
            <Link
              className="chip"
              to={categorySearch(c.id, c.name)}
              aria-label={t('categories.searchIn', { name: c.name })}
            >
              {c.name}
            </Link>
          </li>
        ))}
      </ul>
      <p className="h2-res__note">{t('categories.byCode')}</p>
    </section>
  );
}
