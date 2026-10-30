import type { GuideRequest, SearchQuery, SortOrder, TriState } from '@/api/types';
import { NEW_DESIGN_ENABLED } from '@/lib/features';

export const MULTI_KEYS = [
  'type',
  'source',
  'license',
  'pricing',
  'platform',
  'useCase',
  'techLevel',
  'audience',
] as const satisfies readonly (keyof SearchQuery)[];
export type MultiKey = (typeof MULTI_KEYS)[number];

/** Keys whose API enums spell the unknown bucket in upper case. */
const ENUM_KEYS: readonly MultiKey[] = ['pricing', 'techLevel', 'audience', 'type'];

/** `list`: ranked cards. `timeline`: every result grouped by the month it was created (see lib/timeline). */
export type ResultsView = 'list' | 'timeline';

export interface SearchState {
  q: string;
  view: ResultsView;
  sort: SortOrder;
  page: number;
  updatedAfter: string;
  openSource: TriState | '';
  filters: Record<MultiKey, string[]>;
}

/** Longest search GET /search accepts. */
export const MAX_QUERY = 200;
/** A question for the guide: 3 to 500 characters (GuideRequest.request). */
export const MIN_QUESTION = 3;
export const MAX_QUESTION = 500;

/** The search as the URL and the API carry it: trimmed, at most MAX_QUERY characters. The one place `q` is clamped. */
export function clampQuery(text: string): string {
  return text.trim().slice(0, MAX_QUERY).trimEnd();
}

/** A question the guide accepts (trimmed, at most MAX_QUESTION characters), or '' when it is too short to ask. */
export function clampQuestion(text: string): string {
  const q = text.trim().slice(0, MAX_QUESTION).trimEnd();
  return q.length >= MIN_QUESTION ? q : '';
}

// Records keyed by the contract's enums: a value added to or removed from the API breaks the build here.
const SORT_VALUES = { RELEVANCE: true, NEWEST: true, RECENTLY_UPDATED: true } satisfies Record<SortOrder, true>;
const TRI_VALUES = { true: true, false: true, unknown: true } satisfies Record<TriState, true>;
const SORTS = Object.keys(SORT_VALUES) as SortOrder[];
const TRI = Object.keys(TRI_VALUES) as TriState[];

export function emptyFilters(): Record<MultiKey, string[]> {
  return { type: [], source: [], license: [], pricing: [], platform: [], useCase: [], techLevel: [], audience: [] };
}

export function parseSearchParams(sp: URLSearchParams): SearchState {
  const sort = sp.get('sort');
  const view: ResultsView = sp.get('view') === 'timeline' ? 'timeline' : 'list';
  const os = sp.get('openSource');
  const page = Number.parseInt(sp.get('page') ?? '0', 10);
  const filters = emptyFilters();
  for (const k of MULTI_KEYS) filters[k] = sp.getAll(k).filter(Boolean);
  const updatedAfter = sp.get('updatedAfter') ?? '';
  return {
    q: clampQuery(sp.get('q') ?? ''),
    view,
    sort: SORTS.includes(sort as SortOrder) ? (sort as SortOrder) : 'RELEVANCE',
    page: Number.isFinite(page) && page > 0 ? Math.min(page, 50) : 0,
    updatedAfter: /^\d{4}-\d{2}-\d{2}$/.test(updatedAfter) ? updatedAfter : '',
    openSource: TRI.includes(os as TriState) ? (os as TriState) : '',
    filters,
  };
}

export function toSearchParams(s: SearchState): URLSearchParams {
  const sp = new URLSearchParams();
  const q = clampQuery(s.q);
  if (q) sp.set('q', q);
  if (s.view === 'timeline') sp.set('view', 'timeline');
  else if (s.sort !== 'RELEVANCE') sp.set('sort', s.sort);
  for (const k of MULTI_KEYS) for (const v of s.filters[k]) sp.append(k, v);
  if (s.openSource) sp.set('openSource', s.openSource);
  if (s.updatedAfter) sp.set('updatedAfter', s.updatedAfter);
  if (s.page > 0) sp.set('page', String(s.page));
  return sp;
}

/** Results per page in the timeline: the API maximum, so a month is rarely split across pages. */
export const TIMELINE_PAGE_SIZE = 50;

/**
 * Params for GET /search. The view is UI-only; the timeline asks for NEWEST, the order by the date the source
 * states the item was created, so the API pages through ALL results in creation order (unknown dates last).
 */
export function toApiParams(s: SearchState): URLSearchParams {
  const sp = toSearchParams(s);
  if (s.view === 'timeline') {
    sp.delete('view');
    sp.set('sort', 'NEWEST');
    sp.set('size', String(TIMELINE_PAGE_SIZE));
  }
  return sp;
}

/** Apply a change; every change except paging resets to page 0. Never drops other state. */
export function patchState(s: SearchState, patch: Partial<SearchState>): SearchState {
  const next = { ...s, ...patch };
  if (!('page' in patch)) next.page = 0;
  return next;
}

export function toggleFilter(s: SearchState, key: MultiKey, value: string): SearchState {
  const has = isSelected(s.filters[key], value);
  const list = has ? s.filters[key].filter((v) => v.toLowerCase() !== value.toLowerCase()) : [...s.filters[key], value];
  return patchState(s, { filters: { ...s.filters, [key]: list } });
}

export function isSelected(list: string[], value: string): boolean {
  return list.some((v) => v.toLowerCase() === value.toLowerCase());
}

/** Value to put in the URL for a facet bucket (`unknown` bucket spelled per the API enum). */
export function filterValue(key: MultiKey, facetValue: string): string {
  if (facetValue.toLowerCase() === 'unknown') return ENUM_KEYS.includes(key) ? 'UNKNOWN' : 'unknown';
  return facetValue;
}

export function clearFilters(s: SearchState): SearchState {
  return patchState(s, { filters: emptyFilters(), openSource: '', updatedAfter: '' });
}

export function activeFilterCount(s: SearchState): number {
  return MULTI_KEYS.reduce((n, k) => n + s.filters[k].length, 0) + (s.openSource ? 1 : 0) + (s.updatedAfter ? 1 : 0);
}

/** The question in a home URL (`ask`), or '' when there is none worth asking. */
export function askedQuestion(sp: URLSearchParams): string {
  return clampQuestion(sp.get('ask') ?? '');
}

/**
 * The user's refinement of the question in the URL, sent with it again: the reply to the guide's question back
 * (`clar`; "" means "search anyway") and the parts of the reading they removed (`drop`). In the URL, so a retry and
 * a reload ask the same thing.
 */
export type Refinement = Pick<GuideRequest, 'clarification'> & { drop: string[] };

/** GuideRequest bounds: a reply of at most 300 characters, at most 8 removed parts of at most 80 characters. */
const MAX_CLARIFICATION = 300;
const MAX_DROPS = 8;
const MAX_DROP = 80;

function drops(list: string[]): string[] {
  return [...new Set(list.filter((d) => d && d.length <= MAX_DROP))].slice(0, MAX_DROPS);
}

/** The refinement of the asked question in [sp]; none without a question. The URL is untrusted: cut to the bounds. */
export function askedRefinement(sp: URLSearchParams): Refinement {
  if (!askedQuestion(sp)) return { drop: [] };
  const clar = sp.get('clar');
  const drop = drops(sp.getAll('drop'));
  return clar === null ? { drop } : { clarification: clar.slice(0, MAX_CLARIFICATION), drop };
}

function setRefinement(params: URLSearchParams, r: Refinement) {
  params.delete('clar');
  params.delete('drop');
  if (r.clarification !== undefined && r.clarification !== null) params.set('clar', r.clarification);
  for (const d of r.drop) params.append('drop', d);
}

/** [sp] with [r] added to its refinement: a new reply replaces the old one, removed parts add up. */
export function refinedParams(sp: URLSearchParams, r: Partial<Refinement>): URLSearchParams {
  const prev = askedRefinement(sp);
  const params = new URLSearchParams(sp);
  const clarification = r.clarification ?? prev.clarification;
  const drop = drops([...prev.drop, ...(r.drop ?? [])]);
  setRefinement(params, clarification === undefined ? { drop } : { clarification, drop });
  return params;
}

/**
 * The home URL for [state] plus, optionally, the question the guide should answer. When that is the question
 * [current] already asks, its refinement stays; a new question starts without one.
 */
/**
 * Where results live. Design v2 (ADR 0040, 3) moves them to /search, so / is only the home page; /?q=… keeps
 * working and names /search as the canonical page. With the flag off, results stay on /.
 */
export const RESULTS_PATH = NEW_DESIGN_ENABLED ? '/search' : '/';

export function homeParams(state: SearchState, ask = '', current?: URLSearchParams): URLSearchParams {
  const params = toSearchParams(state);
  const question = clampQuestion(ask);
  if (question) {
    params.set('ask', question);
    if (current && askedQuestion(current) === question) setRefinement(params, askedRefinement(current));
  }
  return params;
}

/** The home URL that searches [question] and asks the guide about it, from any page. */
export function askParams(question: string): URLSearchParams {
  return homeParams(patchState(parseSearchParams(new URLSearchParams()), { q: question }), question);
}
