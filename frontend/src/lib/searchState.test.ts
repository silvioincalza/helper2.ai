import { askedQuestion, askParams, clearFilters, filterValue, homeParams, MAX_QUERY, parseSearchParams, patchState, toApiParams, toggleFilter, toSearchParams } from './searchState';

describe('searchState', () => {
  const sp = new URLSearchParams('q=agents&type=MCP_SERVER&type=MODEL&license=unknown&openSource=true&updatedAfter=2026-01-01&sort=NEWEST&page=2');

  it('round-trips every param', () => {
    const s = parseSearchParams(sp);
    expect(s.filters.type).toEqual(['MCP_SERVER', 'MODEL']);
    expect(toSearchParams(s).toString()).toBe(
      'q=agents&sort=NEWEST&type=MCP_SERVER&type=MODEL&license=unknown&openSource=true&updatedAfter=2026-01-01&page=2',
    );
  });
  it('changing the sort keeps query and filters and resets the page', () => {
    const next = patchState(parseSearchParams(sp), { sort: 'RELEVANCE' });
    const out = toSearchParams(next);
    expect(out.get('q')).toBe('agents');
    expect(out.getAll('type')).toEqual(['MCP_SERVER', 'MODEL']);
    expect(out.get('page')).toBeNull();
  });
  it('ignores invalid values', () => {
    const s = parseSearchParams(new URLSearchParams('sort=X&page=-4&updatedAfter=yesterday&openSource=maybe'));
    expect(s).toMatchObject({ sort: 'RELEVANCE', page: 0, updatedAfter: '', openSource: '' });
  });
  it('drops the old path parameter: one entry point, no scopes', () => {
    expect(toSearchParams(parseSearchParams(new URLSearchParams('q=a&path=BUILD_WITH_AI'))).toString()).toBe('q=a');
  });
  it('toggles filters case-insensitively and clears them', () => {
    const s = toggleFilter(parseSearchParams(new URLSearchParams('q=a&pricing=UNKNOWN')), 'pricing', 'unknown');
    expect(s.filters.pricing).toEqual([]);
    expect(clearFilters(parseSearchParams(sp)).filters.type).toEqual([]);
  });
  it('timeline view lives in the URL, drops the sort and resets the page', () => {
    const next = patchState(parseSearchParams(sp), { view: 'timeline' });
    expect(toSearchParams(next).toString()).toBe(
      'q=agents&view=timeline&type=MCP_SERVER&type=MODEL&license=unknown&openSource=true&updatedAfter=2026-01-01',
    );
    expect(parseSearchParams(toSearchParams(next)).view).toBe('timeline');
    expect(parseSearchParams(new URLSearchParams('view=grid')).view).toBe('list');
  });
  it('the timeline asks the API for creation-date order and big pages; the list sends the chosen sort', () => {
    const timeline = toApiParams(parseSearchParams(new URLSearchParams('q=a&view=timeline&sort=RECENTLY_UPDATED&page=1')));
    expect(timeline.get('view')).toBeNull();
    expect(timeline.get('sort')).toBe('NEWEST');
    expect(timeline.get('size')).toBe('50');
    expect(timeline.get('page')).toBe('1');
    expect(toApiParams(parseSearchParams(sp)).toString()).toBe(toSearchParams(parseSearchParams(sp)).toString());
  });
  it('spells the unknown bucket per API type', () => {
    expect(filterValue('pricing', 'unknown')).toBe('UNKNOWN');
    expect(filterValue('license', 'unknown')).toBe('unknown');
  });

  it('clamps the search to 200 characters, trimmed, both reading and writing the URL', () => {
    const long = 'a'.repeat(300);
    expect(parseSearchParams(new URLSearchParams({ q: `  ${long}` })).q).toHaveLength(MAX_QUERY);
    expect(toSearchParams(patchState(parseSearchParams(new URLSearchParams()), { q: ` ${long} ` })).get('q')).toHaveLength(MAX_QUERY);
    expect(parseSearchParams(new URLSearchParams('q=rag%20')).q).toBe('rag');
  });
  it('asks only a question of 3 to 500 characters, and searches its first 200', () => {
    const question = 'b'.repeat(600);
    const params = askParams(question);
    expect(params.get('ask')).toHaveLength(500);
    expect(params.get('q')).toHaveLength(200);
    expect(askParams('ab').get('ask')).toBeNull();
    expect(askParams('ab').get('q')).toBe('ab');
    expect(homeParams(parseSearchParams(new URLSearchParams('q=x&type=MODEL')), '  why?  ').toString()).toBe('q=x&type=MODEL&ask=why%3F');
    expect(askedQuestion(new URLSearchParams('ask=%20hi%20'))).toBe('');
  });
});
