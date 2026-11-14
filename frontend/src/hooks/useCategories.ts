import { useMemo } from 'react';
import { api } from '@/api/client';
import type { Category } from '@/api/types';
import { useApi } from './useApi';

export interface Categories {
  list: Category[];
  byId: ReadonlyMap<string, Category>;
  loading: boolean;
  error: unknown;
  reload: () => void;
}

export function useCategories(): Categories {
  const res = useApi<Category[]>('categories', (signal) => api.listCategories(signal));
  const list = useMemo(() => res.data ?? [], [res.data]);
  const byId = useMemo(() => new Map(list.map((c) => [c.id, c])), [list]);
  return { list, byId, loading: res.loading, error: res.error, reload: res.reload };
}
