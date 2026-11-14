import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { GuideResponse, SearchResponse } from '@/api/types';
import { makeCard, makeSearch, makeStatus } from '@/test/fixtures';
import { mockApi, sse } from '@/test/mockApi';
import { renderApp } from '@/test/render';
import { mockViewport } from '@/test/viewport';

const source = (sourceId: string, sourceName: string) => ({
  sourceId,
  sourceName,
  url: `https://example.org/${sourceId}`,
  mode: 'LIVE' as const,
  retrievedAt: '2026-10-20T10:00:00Z',
});
const GITHUB = source('github', 'GitHub');
const CATALOG = source('helper2-index', 'helper2.ai catalog');
const BY_MEANING = source('helper2-semantic', 'helper2.ai catalog, by meaning');

const FENCED = makeCard({
  id: 'ffffffff-ffff-5fff-8fff-ffffffffffff',
  title: 'TinyLlama on a Pi',
  description:
    '``` ollama run tinyllama:1.1b --verbose ``` From the benchmark you can see how fast `tinyllama` is.',
  sources: [source('live-web', 'Live web search')],
});

const rowOf = (title: string) =>
  screen
    .getAllByRole('link', { name: title })
    .find((a) => a.classList.contains('h2-row__title'))!
    .closest('li')!;
const announced = () =>
  [...document.querySelectorAll('.h2-results [aria-live="polite"]')]
    .map((e) => e.textContent)
    .filter(Boolean);

function heldSearch(first: SearchResponse) {
  let release: (r: SearchResponse) => void = () => undefined;
  let calls = 0;
  return {
    handler: () => {
      calls++;
      if (calls === 1) return first;
      return new Promise<SearchResponse>((r) => {
        release = r;
      });
    },
    answer: (r: SearchResponse) => act(async () => release(r)),
  };
}

const partial = () =>
  makeSearch({
    sources: [
      makeStatus(),
      makeStatus({ sourceId: 'b', name: 'Beta', outcome: 'FAILED', servedFrom: 'NONE' }),
      makeStatus({ sourceId: 'c', name: 'Gamma', outcome: 'TIMED_OUT', servedFrom: 'NONE' }),
    ],
    coverage: {
      partial: true,
      sourcesConfigured: 3,
      sourcesQueried: 3,
      sourcesSucceeded: 1,
      note: 'n',
    },
  });

describe('Feature: the results read cleaner', () => {
  it('Scenario: a row names each source once, the catalog searched by meaning is the catalog', async () => {
    const both = makeCard({ title: 'Both ways', sources: [GITHUB, CATALOG, BY_MEANING] });
    const meaning = makeCard({
      id: '22222222-2222-5222-8222-222222222222',
      title: 'Only by meaning',
      sources: [BY_MEANING],
    });
    mockApi({
      'GET /search': () =>
        makeSearch({
          results: [both, meaning],
          total: 2,
          sources: [
            makeStatus(),
            makeStatus({ sourceId: CATALOG.sourceId, name: CATALOG.sourceName }),
            makeStatus({ sourceId: BY_MEANING.sourceId, name: BY_MEANING.sourceName }),
          ],
        }),
    });
    renderApp('/search?q=translate');
    await screen.findByText('Both ways');

    expect(within(rowOf('Both ways')).getByText(/^From /)).toHaveTextContent(
      /^From GitHub · helper2\.ai catalog$/,
    );
    expect(within(rowOf('Only by meaning')).getByText(/^From /)).toHaveTextContent(
      /^From helper2\.ai catalog$/,
    );
    const tiles = screen.getByRole('list', { name: 'Sources searched' });
    expect(within(tiles).getByText('helper2.ai catalog, by meaning')).toBeInTheDocument();
  });

  it('Scenario: a description with Markdown code fences shows as plain text, search words still marked', async () => {
    mockApi({ 'GET /search': () => makeSearch({ results: [FENCED] }) });
    renderApp('/search?q=benchmark');
    await screen.findByText('TinyLlama on a Pi');

    const text = rowOf('TinyLlama on a Pi').querySelector('.h2-row__text');
    expect(text).toHaveTextContent(
      'ollama run tinyllama:1.1b --verbose From the benchmark you can see how fast tinyllama is.',
    );
    expect(text?.textContent).not.toContain('`');
    expect(within(text as HTMLElement).getByText('benchmark')).toHaveClass('h2-hit');
  });

  it('Scenario: the timeline shows the same description as plain text', async () => {
    mockApi({ 'GET /search': () => makeSearch({ results: [FENCED] }) });
    renderApp('/search?q=benchmark&view=timeline');
    await screen.findByText('TinyLlama on a Pi');

    expect(document.querySelector('.tl-text')?.textContent).toBe(
      'ollama run tinyllama:1.1b --verbose From the benchmark you can see how fast tinyllama is.',
    );
  });

  it('Scenario: a guide quote that crosses a code fence is still marked inside the text', async () => {
    const answer: GuideResponse = {
      status: 'OK',
      searchQuery: 'tinyllama',
      candidatesConsidered: 1,
      rejected: 0,
      sources: [],
      notices: [],
      generatedAt: '2026-10-25T10:00:00Z',
      model: { provider: 'p', fastModel: 'f', reasoningModel: 'r' },
      recommendations: [
        {
          resourceId: FENCED.id,
          title: FENCED.title,
          url: FENCED.url,
          type: 'PRODUCT',
          reason: 'It shows the speed.',
          quote: '--verbose ``` From the benchmark you can see how fast `tinyllama` is.',
          sourceIds: ['live-web'],
          aiReasoned: true,
          reasonCheck: 'NOT_CHECKED',
        },
      ],
    };
    mockApi({
      'GET /search': () => makeSearch({ results: [FENCED] }),
      'POST /guide/stream': () => sse(answer),
    });
    renderApp('/search?q=tinyllama&ask=tinyllama');
    await screen.findByRole('link', { name: '#1 TinyLlama on a Pi' });

    const proof = rowOf('TinyLlama on a Pi').querySelector('.h2-row__proof') as HTMLElement;
    const mark = proof.querySelector('mark');
    expect(mark).toHaveTextContent(
      '--verbose From the benchmark you can see how fast tinyllama is.',
    );
    expect(mark?.closest('q')).toBeNull();
    expect(proof.querySelector('.h2-row__text')?.textContent).toBe(
      'ollama run tinyllama:1.1b --verbose From the benchmark you can see how fast tinyllama is.',
    );
    expect(within(proof).getByText('Checked word for word in the source')).toBeInTheDocument();
  });
});

describe('Feature: a screen reader hears when a search is done', () => {
  it('Scenario: the result count is said once when the search settles, in a polite live region', async () => {
    mockApi({ 'GET /search': () => makeSearch({ total: 38 }) });
    renderApp('/search?q=translate');
    await screen.findByText('Acme Translator');

    await waitFor(() => expect(announced()).toEqual(['38 results']));
    expect(screen.getByText('38 results')).toHaveClass('sr-only');
  });

  it('Scenario: while the next search runs the old list stays at full strength and nothing is said until it settles', async () => {
    const search = heldSearch(makeSearch({ total: 38 }));
    mockApi({ 'GET /search': search.handler });
    renderApp('/search?q=translate');
    await waitFor(() => expect(announced()).toEqual(['38 results']));

    await userEvent.selectOptions(screen.getByLabelText('Sort by'), 'NEWEST');

    expect(await screen.findByText('Searching the listed sources…')).toBeVisible();
    const kept = screen.getByText('Acme Translator').closest('[aria-busy]');
    expect(kept).toHaveAttribute('aria-busy', 'true');
    expect(kept).not.toHaveClass('is-stale');
    expect(announced()).toEqual([]);

    await search.answer(makeSearch({ total: 1 }));

    await waitFor(() => expect(announced()).toEqual(['1 result']));
    expect(screen.getByText('Acme Translator').closest('[aria-busy]')).toHaveAttribute(
      'aria-busy',
      'false',
    );
  });

  it('Scenario: a search with no result says so once, through its own message, not as a count', async () => {
    mockApi({ 'GET /search': () => makeSearch({ results: [], total: 0 }) });
    renderApp('/search?q=translate');
    await screen.findByText('No results from the sources searched');

    expect(announced().some((text) => /\d+ results?$/.test(text ?? ''))).toBe(false);
  });
});

describe('Feature: the results start higher on a phone', () => {
  it('Scenario: on a phone the sources warning is one short line that keeps both actions', async () => {
    mockViewport(375);
    mockApi({ 'GET /search': partial });
    renderApp('/search?q=translate');
    await screen.findByText('Acme Translator');

    const line = document.querySelector('.h2-partial .h2-coverage') as HTMLElement;
    expect(line).toHaveTextContent(/^\s*2 sources did not answer · Which\? · Try again$/);
    const next = line.querySelector('.h2-coverage__next') as HTMLElement;
    expect(within(next).getByRole('button', { name: 'Which?' })).toBeInTheDocument();
    expect(within(next).getByRole('button', { name: 'Try again' })).toBeInTheDocument();
    const which = within(next).getByRole('button', { name: 'Which?' });
    await userEvent.click(which);
    const missed = document.getElementById(which.getAttribute('aria-controls') ?? '');
    expect(missed).toBeVisible();
    expect(missed).toHaveTextContent('Beta returned an error.');
    expect(missed).toHaveTextContent('Gamma took too long to answer.');
  });

  it('Scenario: on a wide screen the warning still says how many sources were asked', async () => {
    mockViewport(1280);
    mockApi({ 'GET /search': partial });
    renderApp('/search?q=translate');

    expect(await screen.findByText(/2 of 3 sources did not answer/)).toBeInTheDocument();
  });

  it('Scenario: the heading and the sort keep their names when a phone hides their words', async () => {
    mockViewport(375);
    mockApi({ 'GET /search': () => makeSearch() });
    renderApp('/search?q=translate');
    await screen.findByText('Acme Translator');

    expect(screen.getByRole('heading', { level: 2, name: /^Results/ })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Sort by' })).toHaveValue('RELEVANCE');
    expect(screen.getByText('Sort by')).toHaveClass('h2-sort__label');
  });
});
