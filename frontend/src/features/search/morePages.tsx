import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { api } from '@/api/client';
import type { ResultCard, SearchResponse } from '@/api/types';
import { recentAnswer, useApi } from '@/hooks/useApi';

const LAST_PAGE = 50;

interface PageState {
  loading: boolean;
  data?: SearchResponse | undefined;
  error?: unknown;
  reload: () => void;
}

export function useMorePages(first: SearchResponse, apiParams: URLSearchParams, upto: number) {
  const base = apiParams.toString();
  const [pages, setPages] = useState<{ base: string; byPage: Record<number, PageState> }>({
    base,
    byPage: {},
  });
  const byPage = pages.base === base ? pages.byPage : {};
  const onPage = useCallback(
    (page: number, s: PageState) =>
      setPages((p) => ({
        base,
        byPage: { ...(p.base === base ? p.byPage : {}), [page]: s },
      })),
    [base],
  );
  const wanted = Math.min(upto, LAST_PAGE);
  const loaders: ReactNode[] = [];
  for (let page = 1; page <= wanted; page++)
    loaders.push(
      <PageLoader key={`${base}|${page}`} params={apiParams} page={page} onPage={onPage} />,
    );

  const seen = new Set<string>();
  const cards: ResultCard[] = [];
  const add = (list: readonly ResultCard[]) => {
    for (const c of list)
      if (!seen.has(c.id)) {
        seen.add(c.id);
        cards.push(c);
      }
  };
  add(first.results);
  let shownPages = 1;
  let pending: PageState | undefined;
  for (let page = 1; page <= wanted; page++) {
    const s = byPage[page] ?? recentPage(apiParams, page);
    if (!s?.data) {
      pending = s ?? { loading: true, reload: () => undefined };
      break;
    }
    add(s.data.results);
    shownPages++;
  }
  const more = !pending && shownPages <= LAST_PAGE && shownPages * first.size < first.total;
  return {
    cards,
    loaders,
    firstOf: (page: number) => byPage[page]?.data?.results[0]?.id,
    countOf: (page: number) => byPage[page]?.data?.results.length ?? 0,
    loadingMore: pending?.loading ?? false,
    failed: pending && !pending.loading ? pending : undefined,
    more,
    nextPage: shownPages,
  };
}

function pageKey(params: URLSearchParams, page: number): { key: string; params: URLSearchParams } {
  const p = new URLSearchParams(params);
  p.set('page', String(page));
  return { key: `search?${p.toString()}`, params: p };
}

function recentPage(params: URLSearchParams, page: number): PageState | undefined {
  const data = recentAnswer<SearchResponse>(pageKey(params, page).key);
  return data && { loading: false, data, reload: () => undefined };
}

function PageLoader({
  params,
  page,
  onPage,
}: {
  params: URLSearchParams;
  page: number;
  onPage: (page: number, s: PageState) => void;
}) {
  const { key, params: p } = pageKey(params, page);
  const q = useApi<SearchResponse>(key, (signal) => api.search(p, signal));
  const { loading, data, error, reload } = q;
  useEffect(() => {
    onPage(page, { loading, data: loading ? undefined : data, error, reload });
  }, [onPage, page, loading, data, error, reload]);
  return null;
}
