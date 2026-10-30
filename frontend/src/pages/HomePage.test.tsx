import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockApi } from '@/test/mockApi';
import { renderApp } from '@/test/render';
import { makeRecent } from '@/test/fixtures';

describe('home: one page, one box', () => {
  it('has one search box and one button whose job follows the text, with no mode switch', async () => {
    mockApi();
    renderApp('/');
    expect(screen.getAllByRole('search')).toHaveLength(1);
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
    expect(screen.getByText(/A sentence, or Shift\+Enter, asks the guide/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Search' })).toBeDisabled();
    await userEvent.type(screen.getByRole('searchbox'), 'rag');
    expect(screen.getByRole('button', { name: 'Search' })).toBeEnabled();
    await userEvent.type(screen.getByRole('searchbox'), ' for my own notes');
    expect(screen.getByRole('button', { name: 'Ask the guide' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: 'Search' })).not.toBeInTheDocument();
  });

  it('a sentence asks the guide on Enter; Shift+Enter asks even for two words', async () => {
    const m = mockApi();
    const { params } = renderApp('/');
    await userEvent.type(screen.getByRole('searchbox'), 'an MCP server for Postgres{Enter}');
    await waitFor(() => expect(params().get('ask')).toBe('an MCP server for Postgres'));
    await waitFor(() => expect(m.callsTo('POST', '/guide/stream')).toHaveLength(1));
    await userEvent.clear(screen.getByRole('searchbox'));
    await userEvent.type(screen.getByRole('searchbox'), 'vector db{Shift>}{Enter}{/Shift}');
    await waitFor(() => expect(params().get('ask')).toBe('vector db'));
    await waitFor(() => expect(m.callsTo('POST', '/guide/stream')).toHaveLength(2));
  });

  it('Enter searches on the same page', async () => {
    const m = mockApi();
    const { router, params } = renderApp('/');
    await userEvent.type(screen.getByRole('searchbox'), 'mcp github{Enter}');
    await waitFor(() => expect(params().get('q')).toBe('mcp github'));
    expect(router.state.location.pathname).toBe('/');
    expect(await screen.findByText('Acme Translator')).toBeInTheDocument();
    const own = m.callsTo('GET', '/search').find((c) => new URLSearchParams(c.search).get('q') === 'mcp github');
    expect(own).toBeDefined();
    expect(new URLSearchParams(own?.search).get('path')).toBeNull();
  });

  it('typing searches live once the user pauses, not on every letter', async () => {
    const m = mockApi();
    const { params } = renderApp('/');
    await userEvent.type(screen.getByRole('searchbox'), 'rag library');
    await waitFor(() => expect(params().get('q')).toBe('rag library'), { timeout: 2000 });
    await screen.findByText('Acme Translator');
    // One search for the typed text (the Radar reads /recent, not /search).
    const typed = m.callsTo('GET', '/search');
    expect(typed).toHaveLength(1);
    expect(new URLSearchParams(typed[0]?.search).get('q')).toBe('rag library');
  });

  it('an example both searches and asks the guide', async () => {
    const m = mockApi();
    const { params } = renderApp('/');
    await userEvent.click(screen.getByRole('button', { name: 'Free document translation' }));
    await waitFor(() => expect(params().get('ask')).toBe('translate documents between languages for free'));
    expect(params().get('q')).toBe('translate documents between languages for free');
    await waitFor(() => expect(m.callsTo('POST', '/guide/stream')).toHaveLength(1));
    // Regression: a stale live-search timer used to drop `ask` (and the guide answer) ~700 ms later.
    await new Promise((r) => setTimeout(r, 1000));
    expect(params().get('ask')).toBe('translate documents between languages for free');
  });

  it('the empty home shows the Radar from the index: no live search, the last 7 days, one request', async () => {
    const m = mockApi();
    renderApp('/');
    expect(await screen.findByRole('heading', { name: 'What’s new' })).toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: 'Topics' })).not.toBeInTheDocument();
    await screen.findByRole('link', { name: 'Fresh MCP server' });
    expect(m.callsTo('GET', '/search')).toHaveLength(0);
    const first = new URLSearchParams(m.callsTo('GET', '/recent')[0]?.search);
    expect(first.get('days')).toBe('7');
    expect(first.getAll('type')).not.toContain('NEWS');
    expect(m.callsTo('GET', '/recent')).toHaveLength(1);
  });

  it('the hero links to the sources in one line, and how the guide works lives on /mission', async () => {
    mockApi();
    renderApp('/');
    expect(await screen.findByRole('link', { name: /^Searches \d+ disclosed sources?$/ })).toHaveAttribute('href', '/sources');
    expect(screen.queryByRole('heading', { name: 'How the guide answers' })).not.toBeInTheDocument();
  });

  it('the Radar says so when nothing has a stated date, and offers retry on error', async () => {
    mockApi({ 'GET /recent': () => makeRecent({ items: [] }) });
    renderApp('/');
    expect(await screen.findByText(/Nothing with a stated publication date/)).toBeInTheDocument();
  });

  it('the header box works from any page and takes you home', async () => {
    mockApi();
    const { router, params } = renderApp('/mission');
    await userEvent.type(screen.getByRole('searchbox'), 'vector database{Enter}');
    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
    expect(params().get('q')).toBe('vector database');
  });

  it('/saved goes home while Saved is on the roadmap', async () => {
    mockApi();
    const { router } = renderApp('/saved');
    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
  });

  it('a long query suggests asking the guide', async () => {
    mockApi();
    renderApp('/?q=summarise+my+meeting+notes+quickly');
    expect(await screen.findByRole('button', { name: /ask the guide: “summarise my meeting notes quickly”/ })).toBeInTheDocument();
  });

  it('typing then asking at once keeps the question: a pending live search must not drop `ask`', async () => {
    const m = mockApi();
    const { params } = renderApp('/');
    await userEvent.type(screen.getByRole('searchbox'), 'mcp server for slack');
    await userEvent.click(screen.getByRole('button', { name: 'Ask the guide' }));
    await waitFor(() => expect(params().get('ask')).toBe('mcp server for slack'));
    await new Promise((r) => setTimeout(r, 1000)); // past the 700 ms live-search delay
    expect(params().get('ask')).toBe('mcp server for slack');
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(1);
  });

  it('live search does not rewrite the box: a trailing space survives the URL update', async () => {
    mockApi();
    const { params } = renderApp('/');
    await userEvent.type(screen.getByRole('searchbox'), 'rag library ');
    await waitFor(() => expect(params().get('q')).toBe('rag library'), { timeout: 2000 });
    await screen.findByText('Acme Translator');
    expect(screen.getByRole('searchbox')).toHaveValue('rag library ');
  });

  it('a question longer than a search keeps its full text in the box; the search takes its first 200 characters', async () => {
    const m = mockApi();
    const { params } = renderApp('/');
    const question = `an MCP server ${'that works with Postgres '.repeat(12)}`.slice(0, 300).trimEnd();
    expect(question.length).toBeGreaterThan(290);
    await userEvent.click(screen.getByRole('searchbox'));
    await userEvent.paste(question);
    await userEvent.click(screen.getByRole('button', { name: 'Ask the guide' }));
    await waitFor(() => expect(params().get('ask')).toBe(question));
    expect(params().get('q')).toBe(question.slice(0, 200).trimEnd());
    await new Promise((r) => setTimeout(r, 1000)); // past the live-search delay
    expect(screen.getByRole('searchbox')).toHaveValue(question);
    expect(m.callsTo('POST', '/guide/stream')[0]?.body).toEqual({ request: question });
  });

  it('back and forward put the URL query back in the box', async () => {
    mockApi();
    const { router } = renderApp('/?q=first');
    await userEvent.clear(screen.getByRole('searchbox'));
    await userEvent.type(screen.getByRole('searchbox'), 'second{Enter}');
    await waitFor(() => expect(router.state.location.search).toBe('?q=second'));
    await act(() => router.navigate(-1));
    await waitFor(() => expect(screen.getByRole('searchbox')).toHaveValue('first'));
  });

  it('clearing the box and pressing Enter returns to the empty home', async () => {
    mockApi();
    const { router } = renderApp('/?q=translate&type=MODEL');
    await screen.findByText('Acme Translator');
    await userEvent.clear(screen.getByRole('searchbox'));
    await userEvent.type(screen.getByRole('searchbox'), '{Enter}');
    await waitFor(() => expect(router.state.location.search).toBe(''));
    expect(await screen.findByRole('heading', { name: 'Try an example' })).toBeInTheDocument();
  });

  it('the home strip, the search and the Sources page share one GET /sources', async () => {
    const m = mockApi();
    const { router } = renderApp('/');
    await screen.findByRole('link', { name: /^Searches 2 disclosed sources$/ });
    await act(() => router.navigate('/?q=translate'));
    await screen.findByText('Acme Translator');
    await act(() => router.navigate('/sources'));
    await screen.findByRole('table');
    expect(m.callsTo('GET', '/sources')).toHaveLength(1);
  });

  it('the loading feeds nest no status role inside their live regions', async () => {
    mockApi({ 'GET /recent': () => new Promise(() => undefined), 'GET /events/upcoming': () => new Promise(() => undefined) });
    renderApp('/');
    expect(await screen.findByRole('heading', { name: 'What’s new' })).toBeInTheDocument();
    expect(document.querySelectorAll('[aria-busy="true"]').length).toBeGreaterThanOrEqual(2);
    expect(document.querySelectorAll('[aria-live] [role="status"]')).toHaveLength(0);
  });
});
