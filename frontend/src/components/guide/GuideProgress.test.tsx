import { screen, waitFor, within } from '@testing-library/react';
import type { GuideResponse } from '@/api/types';
import { heldSse, mockApi, sse } from '@/test/mockApi';
import { makeSearch, makeStatus } from '@/test/fixtures';
import { renderApp } from '@/test/render';

function answer(over: Partial<GuideResponse> = {}): GuideResponse {
  return {
    status: 'OK',
    searchQuery: 'mcp server postgres',
    recommendations: [],
    prompt: null,
    model: null,
    candidatesConsidered: 42,
    rejected: 0,
    sources: [makeStatus()],
    notices: [],
    intent: 'NEWEST',
    subjects: ['MCP servers'],
    generatedAt: '2026-10-25T10:00:00Z',
    ...over,
  };
}

const URL = '/search?q=new+mcp+servers&ask=new+mcp+servers';
const picksBar = () => screen.getByRole('region', { name: 'Guide picks' });

describe('guide progress while it works', () => {
  it('says how it read the question, then how many results it reads, before the answer', async () => {
    const stream = heldSse();
    mockApi({ 'POST /guide/stream': () => stream.response() });
    renderApp(URL);
    await waitFor(() => expect(picksBar()).toHaveTextContent('Guide is reading the results'));

    stream.step('understood', { intent: 'NEWEST', subjects: ['MCP servers'], window: null });
    const line = await within(picksBar()).findByRole('status');
    await waitFor(() => expect(line).toHaveTextContent('Understood as New resources, MCP servers'));

    stream.step('retrieved', { candidates: 42, sources: [] });
    await waitFor(() => expect(within(picksBar()).getByRole('status')).toHaveTextContent('Guide is reading 42 candidates'));
    expect(within(picksBar()).getByRole('status')).toHaveTextContent('New resources, MCP servers');
    // Nothing to remove yet: a second question would start while the first one still runs.
    expect(screen.queryByRole('button', { name: /and ask again/ })).toBeNull();

    stream.finish(answer());
    expect(await screen.findByRole('button', { name: 'Remove “New resources” and ask again' })).toBeInTheDocument();
  });
});

describe('web searches left today', () => {
  it('shows what the guide has left once one is used', async () => {
    mockApi({ 'POST /guide/stream': () => sse(answer({ webSearches: { limit: 3, remaining: 2 } })) });
    renderApp(URL);
    expect(await screen.findByText('2 of 3 web searches left today for the guide.')).toBeInTheDocument();
  });

  it('says when search has none left, that stored results still answer, and when it resets', async () => {
    mockApi({ 'GET /search': () => makeSearch({ webSearches: { limit: 3, remaining: 0 } }) });
    renderApp('/search?q=postgres');
    const line = await screen.findByText(/No web searches left today for search\./);
    expect(line).toHaveTextContent('Stored results still answer.');
    expect(line).toHaveTextContent(/Resets at \d\d:\d\d your time \(00:00 UTC\)\./);
  });

  it('under a test ceiling (a limit of 100 or more) says only that none is left', async () => {
    mockApi({ 'GET /search': () => makeSearch({ webSearches: { limit: 1000, remaining: 0 } }) });
    renderApp('/search?q=postgres');
    expect(await screen.findByText(/No web searches left today for search\./)).toBeInTheDocument();
  });

  it('says nothing before the first one is used, or when the count is unknown', async () => {
    mockApi({
      'GET /search': () => makeSearch({ webSearches: { limit: 3, remaining: 3 } }),
      'POST /guide/stream': () => sse(answer({ webSearches: null })),
    });
    renderApp(URL);
    await screen.findByRole('button', { name: 'Remove “New resources” and ask again' });
    expect(screen.queryByText(/web searches? left today/)).toBeNull();
  });
});
