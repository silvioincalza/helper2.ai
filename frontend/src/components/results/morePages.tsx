import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { api } from '@/api/client';
import type { ResultCard, SearchResponse } from '@/api/types';
import { recentAnswer, useApi } from '@/hooks/useApi';

/** The API takes pages 0 to 50 (searchState.parseSearchParams caps `page` the same way). */
const LAST_PAGE = 50;

interface PageState {
  loading: boolean;
  data?: SearchResponse | undefined;
  error?: unknown;
  reload: () => void;
}

/**
 * The pages after the first, for "Show more" (ADR 0028 (part was 0042), decision 7). The first page is the results view's own
 * search; `page` in the URL says how many more pages show under it, so Back and a reload come back to the same list.
 * Each page is its own GET /search through useApi, so a page already loaded comes from the recent-answers cache on
 * Back, without a request and without moving the list. On Back the hook mounts again with no page in yet: a page in
 * that cache is read during render, so its rows are in the first paint, before ScrollRestoration puts the old place
 * back (the browser would clamp it to the shorter list and not come back to it). [loaders] render nothing; they hold
 * those requests.
 */
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

  // The rows of every page in order, up to the first one that is not in yet; a row a later page repeats shows once.
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
    /** The first rows of [page], once it is in: where focus goes after Show more. */
    firstOf: (page: number) => byPage[page]?.data?.results[0]?.id,
    /** Rows a page added, for the announcement. */
    countOf: (page: number) => byPage[page]?.data?.results.length ?? 0,
    loadingMore: pending?.loading ?? false,
    failed: pending && !pending.loading ? pending : undefined,
    more,
    nextPage: shownPages,
  };
}

/** The request key of [page]: the same for the loader's request and for reading the cache back. */
function pageKey(params: URLSearchParams, page: number): { key: string; params: URLSearchParams } {
  const p = new URLSearchParams(params);
  p.set('page', String(page));
  return { key: `search?${p.toString()}`, params: p };
}

/** [page] as the recent-answers cache has it, until its loader reports it. */
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
