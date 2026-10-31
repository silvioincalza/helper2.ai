import { askedQuestion, askedRefinement, askParams, clearFilters, filterValue, homeParams, MAX_QUERY, parseSearchParams, patchState, refinedParams, resourcePath, toApiParams, toggleFilter, toSearchParams } from './searchState';

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

describe('the guide refinement in the URL', () => {
  const asked = new URLSearchParams('q=mcp&ask=new+mcp+servers&clar=agents&drop=window&drop=constraint%3Afree');

  it('reads the reply to the guide and the removed parts', () => {
    expect(askedRefinement(asked)).toEqual({ clarification: 'agents', drop: ['window', 'constraint:free'] });
    expect(askedRefinement(new URLSearchParams('ask=x+y+z&clar='))).toEqual({ clarification: '', drop: [] });
    expect(askedRefinement(new URLSearchParams('ask=x+y+z'))).toEqual({ drop: [] });
  });
  it('has no refinement without a question', () => {
    expect(askedRefinement(new URLSearchParams('clar=agents&drop=window'))).toEqual({ drop: [] });
  });
  it('keeps to the bounds the guide accepts: 300 characters of reply, 8 parts of 80 characters, no repeats', () => {
    const sp = new URLSearchParams({ ask: 'new mcp servers', clar: 'c'.repeat(400) });
    for (let i = 0; i < 10; i++) sp.append('drop', `constraint:${i}`);
    sp.append('drop', 'constraint:0');
    sp.append('drop', 'x'.repeat(81));
    sp.append('drop', '');
    const r = askedRefinement(sp);
    expect(r.clarification).toHaveLength(300);
    expect(r.drop).toEqual(['constraint:0', 'constraint:1', 'constraint:2', 'constraint:3', 'constraint:4', 'constraint:5', 'constraint:6', 'constraint:7']);
  });
  it('a refinement adds to the one in the URL: a new reply replaces the old, removed parts add up', () => {
    const next = refinedParams(asked, { clarification: 'coding', drop: ['intent', 'window'] });
    expect(next.get('q')).toBe('mcp');
    expect(askedRefinement(next)).toEqual({ clarification: 'coding', drop: ['window', 'constraint:free', 'intent'] });
    expect(askedRefinement(refinedParams(asked, { drop: ['intent'] })).clarification).toBe('agents');
  });
  it('a search change keeps the refinement of the same question, a new question starts without one', () => {
    const state = patchState(parseSearchParams(asked), { sort: 'NEWEST' });
    expect(askedRefinement(homeParams(state, 'new mcp servers', asked))).toEqual({ clarification: 'agents', drop: ['window', 'constraint:free'] });
    expect(askedRefinement(homeParams(state, 'old mcp servers', asked))).toEqual({ drop: [] });
    expect(homeParams(state, '', asked).has('clar')).toBe(false);
  });
});

describe('the resource page path', () => {
  it('encodes the id, so an id can never add a path segment or a query', () => {
    expect(resourcePath('11111111-1111-5111-8111-111111111111')).toBe('/resource/11111111-1111-5111-8111-111111111111');
    expect(resourcePath('../admin?x=1#y')).toBe('/resource/..%2Fadmin%3Fx%3D1%23y');
  });
});
