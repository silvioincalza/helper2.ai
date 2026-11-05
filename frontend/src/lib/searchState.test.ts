import {
  activeFilterCount,
  askedQuestion,
  askedRefinement,
  askParams,
  clearFilters,
  filterValue,
  hasOwnSearchBox,
  homeParams,
  MAX_QUERY,
  parseSearchParams,
  patchState,
  refinedParams,
  resourcePath,
  toApiParams,
  toggleFilter,
  toSearchParams,
} from './searchState';

describe('searchState', () => {
  const sp = new URLSearchParams(
    'q=agents&type=MCP_SERVER&type=MODEL&license=unknown&openSource=true&updatedAfter=2026-01-01&sort=NEWEST&page=2',
  );

  it('round-trips every param', () => {
    const s = parseSearchParams(sp);
    expect(s.filters.type).toEqual(['MCP_SERVER', 'MODEL']);
    expect(toSearchParams(s).toString()).toBe(
      'q=agents&sort=NEWEST&type=MCP_SERVER&type=MODEL&license=unknown&openSource=true&updatedAfter=2026-01-01&page=2',
    );
  });
  it('keeps the category filter in the URL, several values OR-ed by the API, and clears it with the others', () => {
    const s = parseSearchParams(new URLSearchParams('q=x&category=coding&category=unknown'));
    expect(s.filters.category).toEqual(['coding', 'unknown']);
    expect(toSearchParams(s).getAll('category')).toEqual(['coding', 'unknown']);
    expect(activeFilterCount(s)).toBe(2);
    expect(clearFilters(s).filters.category).toEqual([]);
  });
  it('changing the sort keeps query and filters and resets the page', () => {
    const next = patchState(parseSearchParams(sp), { sort: 'RELEVANCE' });
    const out = toSearchParams(next);
    expect(out.get('q')).toBe('agents');
    expect(out.getAll('type')).toEqual(['MCP_SERVER', 'MODEL']);
    expect(out.get('page')).toBeNull();
  });
  it('ignores invalid values', () => {
    const s = parseSearchParams(
      new URLSearchParams('sort=X&page=-4&updatedAfter=yesterday&openSource=maybe'),
    );
    expect(s).toMatchObject({ sort: 'RELEVANCE', page: 0, updatedAfter: '', openSource: '' });
  });
  it('keeps the audience path a home example set, and drops an unknown one', () => {
    expect(
      toSearchParams(parseSearchParams(new URLSearchParams('q=a&path=BUILD_WITH_AI'))).toString(),
    ).toBe('q=a&path=BUILD_WITH_AI');
    expect(
      toSearchParams(parseSearchParams(new URLSearchParams('q=a&path=ADMIN'))).toString(),
    ).toBe('q=a');
  });
  it('an old link with the audience facet keeps its search and drops the facet: "Best for" is the one audience control', () => {
    const s = parseSearchParams(new URLSearchParams('q=a&audience=USE_AI&type=MODEL'));
    expect(s.filters).not.toHaveProperty('audience');
    expect(toApiParams(s).toString()).toBe('q=a&type=MODEL');
  });
  it('new words drop the path: it belonged to the example, not to what the user types next', () => {
    const fromExample = parseSearchParams(
      new URLSearchParams('q=vector database&path=BUILD_WITH_AI'),
    );
    expect(patchState(fromExample, { sort: 'NEWEST' }).path).toBe('BUILD_WITH_AI');
    expect(patchState(fromExample, { q: 'vector database' }).path).toBe('BUILD_WITH_AI');
    expect(patchState(fromExample, { q: 'graph database' }).path).toBe('');
  });
  it('toggles filters case-insensitively and clears them', () => {
    const s = toggleFilter(
      parseSearchParams(new URLSearchParams('q=a&pricing=UNKNOWN')),
      'pricing',
      'unknown',
    );
    expect(s.filters.pricing).toEqual([]);
    expect(clearFilters(parseSearchParams(sp)).filters.type).toEqual([]);
  });
  it('timeline view lives in the URL, keeps the chosen sort for the list and resets the page', () => {
    const next = patchState(parseSearchParams(sp), { view: 'timeline' });
    expect(toSearchParams(next).toString()).toBe(
      'q=agents&view=timeline&sort=NEWEST&type=MCP_SERVER&type=MODEL&license=unknown&openSource=true&updatedAfter=2026-01-01',
    );
    expect(parseSearchParams(toSearchParams(next)).view).toBe('timeline');
    // Back to the list: the sort chosen before the timeline is still there.
    const back = patchState(
      parseSearchParams(
        toSearchParams(
          patchState(parseSearchParams(new URLSearchParams('q=a&sort=RECENTLY_UPDATED')), {
            view: 'timeline',
          }),
        ),
      ),
      { view: 'list' },
    );
    expect(back.sort).toBe('RECENTLY_UPDATED');
    expect(parseSearchParams(new URLSearchParams('view=grid')).view).toBe('list');
  });
  it('the timeline asks the API for creation-date order and big pages; the list sends the chosen sort', () => {
    const timeline = toApiParams(
      parseSearchParams(new URLSearchParams('q=a&view=timeline&sort=RECENTLY_UPDATED&page=1')),
    );
    expect(timeline.get('view')).toBeNull();
    expect(timeline.get('sort')).toBe('NEWEST');
    expect(timeline.get('size')).toBe('50');
    expect(timeline.get('page')).toBe('1');
    expect(toApiParams(parseSearchParams(sp)).toString()).toBe(
      toSearchParams(parseSearchParams(sp)).toString(),
    );
  });
  it('spells the unknown bucket per API type', () => {
    expect(filterValue('pricing', 'unknown')).toBe('UNKNOWN');
    expect(filterValue('license', 'unknown')).toBe('unknown');
  });

  it('clamps the search to 200 characters, trimmed, both reading and writing the URL', () => {
    const long = 'a'.repeat(300);
    expect(parseSearchParams(new URLSearchParams({ q: `  ${long}` })).q).toHaveLength(MAX_QUERY);
    expect(
      toSearchParams(patchState(parseSearchParams(new URLSearchParams()), { q: ` ${long} ` })).get(
        'q',
      ),
    ).toHaveLength(MAX_QUERY);
    expect(parseSearchParams(new URLSearchParams('q=rag%20')).q).toBe('rag');
  });
  it('asks only a question of 3 to 500 characters, and searches its first 200', () => {
    const question = 'b'.repeat(600);
    const params = askParams(question);
    expect(params.get('ask')).toHaveLength(500);
    expect(params.get('q')).toHaveLength(200);
    expect(askParams('ab').get('ask')).toBeNull();
    expect(askParams('ab').get('q')).toBe('ab');
    expect(
      homeParams(parseSearchParams(new URLSearchParams('q=x&type=MODEL')), '  why?  ').toString(),
    ).toBe('q=x&type=MODEL&ask=why%3F');
    expect(askedQuestion(new URLSearchParams('ask=%20hi%20'))).toBe('');
  });
});

describe('the guide refinement in the URL', () => {
  const asked = new URLSearchParams(
    'q=mcp&ask=new+mcp+servers&clar=agents&drop=window&drop=constraint%3Afree',
  );

  it('reads the reply to the guide and the removed parts', () => {
    expect(askedRefinement(asked)).toEqual({
      clarification: 'agents',
      drop: ['window', 'constraint:free'],
    });
    expect(askedRefinement(new URLSearchParams('ask=x+y+z&clar='))).toEqual({
      clarification: '',
      drop: [],
    });
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
    expect(r.drop).toEqual([
      'constraint:0',
      'constraint:1',
      'constraint:2',
      'constraint:3',
      'constraint:4',
      'constraint:5',
      'constraint:6',
      'constraint:7',
    ]);
  });
  it('a refinement adds to the one in the URL: a new reply replaces the old, removed parts add up', () => {
    const next = refinedParams(asked, { clarification: 'coding', drop: ['intent', 'window'] });
    expect(next.get('q')).toBe('mcp');
    expect(askedRefinement(next)).toEqual({
      clarification: 'coding',
      drop: ['window', 'constraint:free', 'intent'],
    });
    expect(askedRefinement(refinedParams(asked, { drop: ['intent'] })).clarification).toBe(
      'agents',
    );
  });
  it('a search change keeps the refinement of the same question, a new question starts without one', () => {
    const state = patchState(parseSearchParams(asked), { sort: 'NEWEST' });
    expect(askedRefinement(homeParams(state, 'new mcp servers', asked))).toEqual({
      clarification: 'agents',
      drop: ['window', 'constraint:free'],
    });
    expect(askedRefinement(homeParams(state, 'old mcp servers', asked))).toEqual({ drop: [] });
    expect(homeParams(state, '', asked).has('clar')).toBe(false);
  });
});

describe('the resource page path', () => {
  it('encodes the id, so an id can never add a path segment or a query', () => {
    expect(resourcePath('11111111-1111-5111-8111-111111111111')).toBe(
      '/resource/11111111-1111-5111-8111-111111111111',
    );
    expect(resourcePath('../admin?x=1#y')).toBe('/resource/..%2Fadmin%3Fx%3D1%23y');
  });
});

describe('hasOwnSearchBox', () => {
  it('/ and /search draw their own box, every other page uses the header one', () => {
    expect(hasOwnSearchBox('/')).toBe(true);
    expect(hasOwnSearchBox('/search')).toBe(true);
    expect(hasOwnSearchBox('/sources')).toBe(false);
  });
});
