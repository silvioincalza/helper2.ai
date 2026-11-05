import { useMemo } from 'react';
import { api } from '@/api/client';
import type { Category } from '@/api/types';
import { useApi } from './useApi';

export interface Categories {
  /** Every category, in the configured order; empty while loading or after an error. */
  list: Category[];
  byId: ReadonlyMap<string, Category>;
  loading: boolean;
  error: unknown;
  reload: () => void;
}

/**
 * The categories search filters by (GET /categories, ADR 0053). Our own fixed words from the configuration, the same for
 * everyone: one request per page view, then the browser's cache and the recent-answers cache.
 */
export function useCategories(): Categories {
  const res = useApi<Category[]>('categories', (signal) => api.listCategories(signal));
  const list = useMemo(() => res.data ?? [], [res.data]);
  const byId = useMemo(() => new Map(list.map((c) => [c.id, c])), [list]);
  return { list, byId, loading: res.loading, error: res.error, reload: res.reload };
}
