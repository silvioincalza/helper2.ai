import {
  audiencePathValues,
  pricingFilterValues,
  resourceTypeValues,
  sortOrderValues,
  techLevelFilterValues,
  triStateValues,
} from '@/api/schema';
import type { AudiencePath, GuideRequest, SearchQuery, SortOrder, TriState } from '@/api/types';
import { slugOf } from './slug';

export const MULTI_KEYS = [
  'type',
  'source',
  'license',
  'pricing',
  'platform',
  'useCase',
  'techLevel',
  'category',
] as const satisfies readonly (keyof SearchQuery)[];
export type MultiKey = (typeof MULTI_KEYS)[number];

const ENUM_KEYS: readonly MultiKey[] = ['pricing', 'techLevel', 'type'];

interface FilterRule {
  maxItems: number;
  accepts: (value: string) => boolean;
}

const oneOf =
  (values: readonly string[]) =>
  (value: string): boolean =>
    values.includes(value);
const upTo =
  (maxLength: number) =>
  (value: string): boolean =>
    value.length <= maxLength;
const CATEGORY_ID = /^[a-z0-9]+(-[a-z0-9]+)*$/;

const FILTER_RULES: Record<MultiKey, FilterRule> = {
  type: { maxItems: 20, accepts: oneOf(resourceTypeValues) },
  source: { maxItems: 20, accepts: upTo(40) },
  license: { maxItems: 20, accepts: upTo(60) },
  pricing: { maxItems: 20, accepts: oneOf(pricingFilterValues) },
  platform: { maxItems: 20, accepts: upTo(40) },
  useCase: { maxItems: 20, accepts: upTo(40) },
  techLevel: { maxItems: 20, accepts: oneOf(techLevelFilterValues) },
  category: { maxItems: 40, accepts: (v) => v.length <= 30 && CATEGORY_ID.test(v) },
};

function validFilter(key: MultiKey, values: string[]): string[] {
  const rule = FILTER_RULES[key];
  return values.filter((v) => v && rule.accepts(v)).slice(0, rule.maxItems);
}

export type ResultsView = 'list' | 'timeline';

export interface SearchState {
  q: string;
  view: ResultsView;
  sort: SortOrder;
  page: number;
  updatedAfter: string;
  openSource: TriState | '';
  filters: Record<MultiKey, string[]>;
  path: AudiencePath | '';
}

export const MAX_QUERY = 200;
export const MIN_QUESTION = 3;
export const MAX_QUESTION = 500;

export function clampQuery(text: string): string {
  return text.trim().slice(0, MAX_QUERY).trimEnd();
}

export function clampQuestion(text: string): string {
  const q = text.trim().slice(0, MAX_QUESTION).trimEnd();
  return q.length >= MIN_QUESTION ? q : '';
}

function emptyFilters(): Record<MultiKey, string[]> {
  return {
    type: [],
    source: [],
    license: [],
    pricing: [],
    platform: [],
    useCase: [],
    techLevel: [],
    category: [],
  };
}

function isCalendarDay(text: string): boolean {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text);
  if (!m) return false;
  const [year, month, day] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const date = new Date(Date.UTC(year, month - 1, day));
  return (
    date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
  );
}

export function parseSearchParams(sp: URLSearchParams): SearchState {
  const sort = sp.get('sort');
  const view: ResultsView = sp.get('view') === 'timeline' ? 'timeline' : 'list';
  const os = sp.get('openSource');
  const page = Number.parseInt(sp.get('page') ?? '0', 10);
  const filters = emptyFilters();
  for (const k of MULTI_KEYS) filters[k] = validFilter(k, sp.getAll(k));
  const updatedAfter = sp.get('updatedAfter') ?? '';
  return {
    q: clampQuery(sp.get('q') ?? ''),
    view,
    sort: sortOrderValues.includes(sort as SortOrder) ? (sort as SortOrder) : 'RELEVANCE',
    page: Number.isFinite(page) && page > 0 ? Math.min(page, 50) : 0,
    updatedAfter: isCalendarDay(updatedAfter) ? updatedAfter : '',
    openSource: triStateValues.includes(os as TriState) ? (os as TriState) : '',
    filters,
    path: audiencePathValues.includes(sp.get('path') as AudiencePath)
      ? (sp.get('path') as AudiencePath)
      : '',
  };
}

export function toSearchParams(s: SearchState): URLSearchParams {
  const sp = new URLSearchParams();
  const q = clampQuery(s.q);
  if (q) sp.set('q', q);
  if (s.view === 'timeline') sp.set('view', 'timeline');
  if (s.sort !== 'RELEVANCE') sp.set('sort', s.sort);
  for (const k of MULTI_KEYS) for (const v of s.filters[k]) sp.append(k, v);
  if (s.openSource) sp.set('openSource', s.openSource);
  if (s.updatedAfter) sp.set('updatedAfter', s.updatedAfter);
  if (s.path) sp.set('path', s.path);
  if (s.page > 0) sp.set('page', String(s.page));
  return sp;
}

const TIMELINE_PAGE_SIZE = 50;

export function toApiParams(s: SearchState): URLSearchParams {
  const sp = toSearchParams(s);
  if (s.view === 'timeline') {
    sp.delete('view');
    sp.set('sort', 'NEWEST');
    sp.set('size', String(TIMELINE_PAGE_SIZE));
  }
  return sp;
}

export function patchState(s: SearchState, patch: Partial<SearchState>): SearchState {
  const next = { ...s, ...patch };
  if (!('page' in patch)) next.page = 0;
  if (patch.q !== undefined && patch.q !== s.q && !('path' in patch)) next.path = '';
  return next;
}

export function toggleFilter(s: SearchState, key: MultiKey, value: string): SearchState {
  const has = isSelected(s.filters[key], value);
  const list = has
    ? s.filters[key].filter((v) => v.toLowerCase() !== value.toLowerCase())
    : [...s.filters[key], value];
  return patchState(s, { filters: { ...s.filters, [key]: list } });
}

export function isSelected(list: string[], value: string): boolean {
  return list.some((v) => v.toLowerCase() === value.toLowerCase());
}

export function filterValue(key: MultiKey, facetValue: string): string {
  if (facetValue.toLowerCase() === 'unknown')
    return ENUM_KEYS.includes(key) ? 'UNKNOWN' : 'unknown';
  return facetValue;
}

export function clearFilters(s: SearchState): SearchState {
  return patchState(s, { filters: emptyFilters(), openSource: '', updatedAfter: '' });
}

export function activeFilterCount(s: SearchState): number {
  return (
    MULTI_KEYS.reduce((n, k) => n + s.filters[k].length, 0) +
    (s.openSource ? 1 : 0) +
    (s.updatedAfter ? 1 : 0)
  );
}

export function askedQuestion(sp: URLSearchParams): string {
  return clampQuestion(sp.get('ask') ?? '');
}

export type Refinement = Pick<GuideRequest, 'clarification'> & { drop: string[] };

export const MAX_CLARIFICATION = 300;
export const MAX_DROPS = 8;
export const MAX_DROP = 80;

function drops(list: string[]): string[] {
  return [...new Set(list.filter((d) => d && d.length <= MAX_DROP))].slice(0, MAX_DROPS);
}

export function askedRefinement(sp: URLSearchParams): Refinement {
  if (!askedQuestion(sp)) return { drop: [] };
  const clar = sp.get('clar');
  const drop = drops(sp.getAll('drop'));
  return clar === null ? { drop } : { clarification: clar.slice(0, MAX_CLARIFICATION), drop };
}

function setRefinement(params: URLSearchParams, r: Refinement) {
  params.delete('clar');
  params.delete('drop');
  if (r.clarification !== undefined && r.clarification !== null)
    params.set('clar', r.clarification);
  for (const d of r.drop) params.append('drop', d);
}

export function refinedParams(sp: URLSearchParams, r: Partial<Refinement>): URLSearchParams {
  const prev = askedRefinement(sp);
  const params = new URLSearchParams(sp);
  const clarification = r.clarification ?? prev.clarification;
  const drop = drops([...prev.drop, ...(r.drop ?? [])]);
  setRefinement(params, clarification === undefined ? { drop } : { clarification, drop });
  return params;
}

export const RESULTS_PATH = '/search';

export function hasOwnSearchBox(pathname: string): boolean {
  return pathname === '/' || pathname === RESULTS_PATH;
}

export function showsHome(pathname: string, sp: URLSearchParams): boolean {
  return pathname === '/' && !parseSearchParams(sp).q && !askedQuestion(sp);
}

export function resourcePath(id: string, title?: string | null): string {
  const slug = slugOf(title);
  return `/resource/${encodeURIComponent(id)}${slug ? `/${slug}` : ''}`;
}

export function homeParams(
  state: SearchState,
  ask = '',
  current?: URLSearchParams,
): URLSearchParams {
  const params = toSearchParams(state);
  const question = clampQuestion(ask);
  if (question) {
    params.set('ask', question);
    if (current && askedQuestion(current) === question)
      setRefinement(params, askedRefinement(current));
  }
  return params;
}

export interface AskedNow {
  askedAt: number;
}

export function askedNow(): AskedNow {
  return { askedAt: Date.now() };
}

export function askToken(state: unknown): number | undefined {
  if (!state || typeof state !== 'object') return undefined;
  const at = (state as Record<string, unknown>).askedAt;
  return typeof at === 'number' && Number.isFinite(at) ? at : undefined;
}
