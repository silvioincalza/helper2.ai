import { parseReviewFilters, withReviewFilter } from './useReviewFilters';

const parse = (query: string) => parseReviewFilters(new URLSearchParams(query));

describe('the review queue filters in the address', () => {
  it('reads an empty address as the first page of every open item', () => {
    expect(parse('')).toEqual({
      status: 'OPEN',
      kind: undefined,
      category: undefined,
      page: 0,
      research: undefined,
      byTarget: false,
    });
  });

  it('reads every filter it knows', () => {
    expect(
      parse('status=REJECTED&kind=USER_REPORT&category=self-hosted&page=3&research=KEEP'),
    ).toEqual({
      status: 'REJECTED',
      kind: 'USER_REPORT',
      category: 'self-hosted',
      page: 3,
      research: 'KEEP',
      byTarget: false,
    });
  });

  it('drops a value it does not know instead of sending it to the API', () => {
    expect(parse('status=DONE&kind=OTHER&category=Bad_Slug&page=-2&research=MAYBE')).toEqual(
      parse(''),
    );
    expect(parse('page=abc').page).toBe(0);
    expect(parse('category=-coding').category).toBeUndefined();
  });

  it('groups open relationship candidates by target unless the address asks for items', () => {
    expect(parse('kind=RELATIONSHIP_CANDIDATE').byTarget).toBe(true);
    expect(parse('kind=RELATIONSHIP_CANDIDATE&view=items').byTarget).toBe(false);
    expect(parse('kind=RELATIONSHIP_CANDIDATE&status=ACCEPTED').byTarget).toBe(false);
    expect(parse('kind=USER_REPORT').byTarget).toBe(false);
  });

  it('writes a filter and reads it back', () => {
    let params = new URLSearchParams();
    params = withReviewFilter(params, 'status', 'ACCEPTED');
    params = withReviewFilter(params, 'kind', 'RESOURCE_CANDIDATE');
    params = withReviewFilter(params, 'category', 'coding');
    params = withReviewFilter(params, 'research', 'DUPLICATE');
    params = withReviewFilter(params, 'page', '2');

    expect(params.toString()).toBe(
      'status=ACCEPTED&kind=RESOURCE_CANDIDATE&category=coding&research=DUPLICATE&page=2',
    );
    expect(parseReviewFilters(params)).toEqual({
      status: 'ACCEPTED',
      kind: 'RESOURCE_CANDIDATE',
      category: 'coding',
      page: 2,
      research: 'DUPLICATE',
      byTarget: false,
    });
  });

  it('goes back to the first page when a filter other than the page changes', () => {
    const at = new URLSearchParams('kind=USER_REPORT&page=4');

    expect(withReviewFilter(at, 'status', 'REJECTED').toString()).toBe(
      'kind=USER_REPORT&status=REJECTED',
    );
    expect(withReviewFilter(at, 'page', '5').toString()).toBe('kind=USER_REPORT&page=5');
  });

  it('removes a cleared filter, keeps the others and leaves the given address as it was', () => {
    const at = new URLSearchParams('kind=USER_REPORT&research=KEEP&other=1');

    expect(withReviewFilter(at, 'research', undefined).toString()).toBe('kind=USER_REPORT&other=1');
    expect(at.toString()).toBe('kind=USER_REPORT&research=KEEP&other=1');
  });
});
