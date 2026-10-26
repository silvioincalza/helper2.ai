import { createContext, useContext, type ReactNode } from 'react';
import { api } from '@/api/client';
import type { SourceInfo } from '@/api/types';
import { useApi, type ApiState } from '@/hooks/useApi';

const SourcesContext = createContext<ApiState<SourceInfo[]> | null>(null);

/**
 * The disclosed source registry (GET /sources), fetched once per page load and shared by the home strip, the
 * per-source "querying…" lines and the Sources page, instead of one request each.
 */
export function SourcesProvider({ children }: { children: ReactNode }) {
  const sources = useApi('sources', (signal) => api.listSources(signal));
  return <SourcesContext.Provider value={sources}>{children}</SourcesContext.Provider>;
}

export function useSources(): ApiState<SourceInfo[]> {
  const sources = useContext(SourcesContext);
  if (!sources) throw new Error('useSources needs a SourcesProvider');
  return sources;
}
