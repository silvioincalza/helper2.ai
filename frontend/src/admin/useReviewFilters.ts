import { useSearchParams } from 'react-router';
import type { ReviewKind, ReviewStatus } from '@/api/types';
import { REVIEW_KINDS, REVIEW_STATUSES, oneOf } from './adminModel';
import { RESEARCH_FILTERS } from './ReviewResearch';

const CATEGORY = /^[a-z0-9]+(-[a-z0-9]+)*$/;

type ResearchFilter = (typeof RESEARCH_FILTERS)[number];
type ReviewFilterName = 'status' | 'kind' | 'category' | 'page' | 'view' | 'research';

export interface ReviewFilters {
  status: ReviewStatus;
  kind: ReviewKind | undefined;
  category: string | undefined;
  page: number;
  research: ResearchFilter | undefined;
  byTarget: boolean;
}

export function parseReviewFilters(params: URLSearchParams): ReviewFilters {
  const status = oneOf(params.get('status'), REVIEW_STATUSES) ?? 'OPEN';
  const kind = oneOf(params.get('kind'), REVIEW_KINDS);
  const wanted = params.get('category');
  return {
    status,
    kind,
    category: wanted && CATEGORY.test(wanted) ? wanted : undefined,
    page: Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0),
    research: oneOf(params.get('research'), RESEARCH_FILTERS),
    byTarget:
      status === 'OPEN' && kind === 'RELATIONSHIP_CANDIDATE' && params.get('view') !== 'items',
  };
}

export function withReviewFilter(
  params: URLSearchParams,
  name: ReviewFilterName,
  value: string | undefined,
): URLSearchParams {
  const next = new URLSearchParams(params);
  if (value === undefined) next.delete(name);
  else next.set(name, value);
  if (name !== 'page') next.delete('page');
  return next;
}

export function useReviewFilters() {
  const [params, setParams] = useSearchParams();
  const setFilter = (name: ReviewFilterName, value: string | undefined) =>
    setParams(withReviewFilter(params, name, value));
  return { ...parseReviewFilters(params), setFilter };
}
