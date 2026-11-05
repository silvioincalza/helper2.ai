import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axeViolations } from '@/test/a11y';
import { CATEGORIES, makeCard, makeDetail, makeSearch } from '@/test/fixtures';
import { mockApi, problem, sse } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

/** Categories as a search filter (ADR 0053), as a person uses them. */
const search = () =>
  makeSearch({
    facets: {
      ...makeSearch().facets,
      category: [
        { value: 'coding', count: 4 },
        { value: 'translation', count: 2 },
        { value: 'unknown', count: 3 },
      ],
    },
  });

async function openFilters() {
  await userEvent.click(await screen.findByRole('button', { name: /^Filters/ }));
  return screen.findByRole('group', { name: 'Category' });
}

describe('Feature: filter results by category', () => {
  it('Scenario: the Category facet groups the categories by family, with counts and "No category yet"', async () => {
    mockApi({ 'GET /search': () => search() });
    renderApp('/search?q=tools');
    const facet = await openFilters();

    const everyday = await within(facet).findByRole('group', { name: 'Everyday apps' });
    expect(within(everyday).getByRole('checkbox', { name: 'Translation (2)' })).not.toBeChecked();
    // A category with no result in this search is not offered.
    expect(within(everyday).queryByRole('checkbox', { name: /Writing/ })).toBeNull();
    const developers = within(facet).getByRole('group', { name: 'Developers' });
    expect(within(developers).getByRole('checkbox', { name: 'Coding (4)' })).toBeInTheDocument();
    expect(
      within(facet).getByRole('checkbox', { name: 'No category yet (3)' }),
    ).toBeInTheDocument();
  });

  it('Scenario: choosing a category puts it in the URL, searches with it and shows it as a chip', async () => {
    const api = mockApi({ 'GET /search': () => search() });
    const { params } = renderApp('/search?q=tools');
    const facet = await openFilters();

    await userEvent.click(await within(facet).findByRole('checkbox', { name: 'Coding (4)' }));

    expect(params().getAll('category')).toEqual(['coding']);
    await waitFor(() =>
      expect(api.callsTo('GET', '/search').at(-1)?.search).toContain('category=coding'),
    );
    const chip = await screen.findByRole('button', { name: 'Remove filter: Category: Coding' });
    await userEvent.click(chip);
    expect(params().getAll('category')).toEqual([]);
  });

  it('Scenario: a category in a shared link is checked when the page opens', async () => {
    mockApi({ 'GET /search': () => search() });
    renderApp('/search?q=tools&category=coding&category=unknown');
    const facet = await openFilters();

    expect(await within(facet).findByRole('checkbox', { name: 'Coding (4)' })).toBeChecked();
    expect(within(facet).getByRole('checkbox', { name: 'No category yet (3)' })).toBeChecked();
    expect(
      screen.getByRole('button', { name: 'Remove filter: Category: No category yet' }),
    ).toBeInTheDocument();
  });

  it('Scenario: the category names cannot be loaded; the facet says so and tries again', async () => {
    let fail = true;
    mockApi({
      'GET /search': () => search(),
      'GET /categories': () => (fail ? problem(503, 'Unavailable') : CATEGORIES),
    });
    renderApp('/search?q=tools');
    const facet = await openFilters();

    expect(await within(facet).findByRole('alert')).toHaveTextContent(
      'Could not load the categories.',
    );
    fail = false;
    await userEvent.click(within(facet).getByRole('button', { name: 'Try again' }));
    expect(await within(facet).findByRole('checkbox', { name: 'Coding (4)' })).toBeInTheDocument();
  });

  it('Scenario: no result has a category yet', async () => {
    mockApi({
      'GET /search': () =>
        makeSearch({
          facets: { ...makeSearch().facets, category: [{ value: 'unknown', count: 1 }] },
        }),
    });
    renderApp('/search?q=tools');
    const facet = await openFilters();

    expect(
      await within(facet).findByText('None of these results has a category yet.'),
    ).toBeInTheDocument();
    expect(
      within(facet).getByRole('checkbox', { name: 'No category yet (1)' }),
    ).toBeInTheDocument();
  });

  it('Scenario: the guide asked from a filtered page picks in those categories', async () => {
    const api = mockApi({
      'GET /search': () => search(),
      'POST /guide/stream': () =>
        sse({
          status: 'NO_CANDIDATES',
          searchQuery: 'tools',
          candidatesConsidered: 0,
          rejected: 0,
          sources: [],
          notices: [],
          generatedAt: '2026-10-25T10:00:00Z',
          prompt: null,
          recommendations: [],
        }),
    });
    renderApp('/search?q=tools&ask=tools%20for%20code&category=coding');

    await waitFor(() => expect(api.callsTo('POST', '/guide/stream')).toHaveLength(1));
    expect(api.callsTo('POST', '/guide/stream')[0]!.body).toMatchObject({
      request: 'tools for code',
      categories: ['coding'],
    });
  });

  it('Scenario: the open Filters panel with categories has no axe violations', async () => {
    mockApi({ 'GET /search': () => search() });
    const { container } = renderApp('/search?q=tools');
    const facet = await openFilters();
    await within(facet).findByRole('checkbox', { name: 'Coding (4)' });
    await settle();
    expect(await axeViolations(container)).toEqual([]);
  });
});

describe('Feature: a resource page shows its categories', () => {
  it('Scenario: each category links to the search filtered by it', async () => {
    const card = makeCard({ categories: ['coding', 'translation'] });
    mockApi({ 'GET /resources/*': () => makeDetail({ card }) });
    renderApp(`/resource/${card.id}`);

    const section = await screen.findByRole('region', { name: 'Categories' });
    const coding = await within(section).findByRole('link', { name: 'Search in Coding' });
    const href = new URL(coding.getAttribute('href')!, 'https://helper2.ai');
    expect(href.pathname).toBe('/search');
    expect(href.searchParams.get('q')).toBe('Coding');
    expect(href.searchParams.getAll('category')).toEqual(['coding']);
    expect(within(section).getByText(/not by AI/)).toBeInTheDocument();
  });

  it('Scenario: a resource with no category shows no Categories section', async () => {
    const card = makeCard({ categories: [] });
    mockApi({ 'GET /resources/*': () => makeDetail({ card }) });
    renderApp(`/resource/${card.id}`);

    await screen.findByRole('heading', { level: 1, name: card.title });
    await settle();
    expect(screen.queryByRole('region', { name: 'Categories' })).toBeNull();
  });
});
