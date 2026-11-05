import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { makeSearch } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

/** The result list: a title also shows in the preview of the selected row. */
const results = async () => within(await screen.findByRole('list', { name: 'Search results' }));

describe('one box', () => {
  it('has one search box with two buttons, Search and Ask the guide, for 1 word and for 8 words', async () => {
    mockApi();
    renderApp('/');
    expect(screen.getAllByRole('search')).toHaveLength(1);
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
    const box = screen.getByRole('textbox', { name: 'Search AI resources' });
    const both = () => [
      screen.getByRole('button', { name: 'Search' }),
      screen.getByRole('button', { name: 'Ask the guide' }),
    ];
    expect(both().every((b) => b.matches(':enabled'))).toBe(true);
    await userEvent.type(box, 'rag');
    expect(both().every((b) => b.matches(':enabled'))).toBe(true);
    await userEvent.type(box, ' for my own notes and my own papers');
    expect(both().every((b) => b.matches(':enabled'))).toBe(true);
  });

  it('given an empty box, when the user clicks either button, then the cursor goes to the box and nothing is sent', async () => {
    const m = mockApi();
    const { router } = renderApp('/');
    const box = screen.getByRole('textbox', { name: 'Search AI resources' });
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    expect(box).toHaveFocus();
    await userEvent.click(document.body);
    await userEvent.click(screen.getByRole('button', { name: 'Ask the guide' }));
    expect(box).toHaveFocus();
    expect(router.state.location.pathname).toBe('/');
    expect(m.callsTo('GET', '/search')).toHaveLength(0);
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(0);
  });

  it('shows the key of each button: ↵ on Search, ⇧↵ on Ask the guide', async () => {
    mockApi();
    renderApp('/');
    expect(screen.getByRole('button', { name: 'Search' }).querySelector('kbd')).toHaveTextContent(
      /^↵$/,
    );
    expect(
      screen.getByRole('button', { name: 'Ask the guide' }).querySelector('kbd'),
    ).toHaveTextContent(/^⇧↵$/);
    await settle();
  });

  it('on a phone, Ask the guide is an icon button with its accessible name and a tooltip', async () => {
    mockApi();
    renderApp('/');
    const guide = screen.getByRole('button', { name: 'Ask the guide' });
    expect(guide).toHaveAttribute('title', 'Ask the guide');
    expect(guide.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
    // The narrow box hides the label and the key hint and keeps a 44 px target.
    const css = readFileSync(join(process.cwd(), 'src/styles/app.css'), 'utf8');
    expect(css).toMatch(
      /\.one-box-ask \{[^}]*min-width: var\(--target-min\);[^}]*min-height: var\(--target-min\);/,
    );
    expect(css).toMatch(
      /@media \(max-width: 520px\) \{[^@]*\.searchbox\.large \.enter-key \{\s*display: none;[^@]*\.one-box-ask__label \{[^}]*clip: rect\(0, 0, 0, 0\);/,
    );
    await settle();
  });

  it('given a desktop, the key hints show on Home and in the header; given a touch device, they are hidden', () => {
    const v4 = readFileSync(join(process.cwd(), 'src/styles/v4.css'), 'utf8');
    const outsideMedia = v4.replace(/@media[^{]*\{(?:[^{}]*\{[^}]*\})*\s*\}/g, '');
    expect(outsideMedia).not.toMatch(/\.enter-key[^{]*\{[^}]*display: none/);
    for (const file of ['src/styles/app.css', 'src/styles/v4.css']) {
      const css = readFileSync(join(process.cwd(), file), 'utf8');
      expect(css).toMatch(
        /@media \(hover: none\), \(pointer: coarse\) \{[^}]*\.enter-key \{\s*display: none;/,
      );
    }
  });

  it('Enter on the home page opens the results on /search, and focus lands on the results heading', async () => {
    const m = mockApi();
    const { router, params } = renderApp('/');
    await userEvent.type(
      screen.getByRole('textbox', { name: 'Search AI resources' }),
      'mcp github{Enter}',
    );
    await waitFor(() => expect(router.state.location.pathname).toBe('/search'));
    expect(params().get('q')).toBe('mcp github');
    expect(await (await results()).findByText('Acme Translator')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).toHaveFocus());
    const own = m
      .callsTo('GET', '/search')
      .find((c) => new URLSearchParams(c.search).get('q') === 'mcp github');
    expect(new URLSearchParams(own?.search).get('path')).toBeNull();
    await settle();
  });

  it('on the results, typing searches live once the user pauses, not on every letter', async () => {
    const m = mockApi();
    const { params } = renderApp('/search?q=mcp');
    await (await results()).findByText('Acme Translator');
    await userEvent.clear(screen.getByRole('textbox', { name: 'Search AI resources' }));
    await userEvent.type(
      screen.getByRole('textbox', { name: 'Search AI resources' }),
      'rag library',
    );
    await waitFor(() => expect(params().get('q')).toBe('rag library'), { timeout: 2000 });
    await settle();
    expect(m.callsTo('GET', '/search').map((c) => new URLSearchParams(c.search).get('q'))).toEqual([
      'mcp',
      'rag library',
    ]);
  });

  it('an example searches with its audience path, and does not spend a guide answer', async () => {
    vi.spyOn(Math, 'random').mockReturnValue(0);
    const m = mockApi();
    const { params } = renderApp('/');
    await userEvent.click(await screen.findByRole('button', { name: 'Summarise research papers' }));
    await waitFor(() => expect(params().get('q')).toBe('summarise research papers'));
    expect(params().get('path')).toBe('USE_AI');
    expect(params().get('ask')).toBeNull();
    await waitFor(() => expect(m.callsTo('GET', '/search')).toHaveLength(1));
    expect(new URLSearchParams(m.callsTo('GET', '/search')[0]?.search).get('path')).toBe('USE_AI');
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(0);
    vi.restoreAllMocks();
  });

  it('shows two examples for people who use AI and two for builders', async () => {
    vi.spyOn(Math, 'random').mockReturnValue(0);
    mockApi();
    renderApp('/');
    const list = await within(
      (await screen.findByRole('heading', { name: 'Try:' })).closest('section') as HTMLElement,
    ).findAllByRole('button');
    expect(list.map((b) => b.textContent)).toEqual([
      'Summarise research papers',
      'Take meeting notes',
      'Postgres MCP server',
      'Vector database',
    ]);
    vi.restoreAllMocks();
  });

  it('the header box works from any page and opens the results', async () => {
    mockApi();
    const { router, params } = renderApp('/mission');
    await userEvent.type(
      await screen.findByRole('textbox', { name: 'Search AI resources' }),
      'vector database{Enter}',
    );
    await waitFor(() => expect(router.state.location.pathname).toBe('/search'));
    expect(params().get('q')).toBe('vector database');
    await settle();
  });

  it('/saved is the saved page', async () => {
    mockApi();
    renderApp('/saved');
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Saved resources' }),
    ).toBeInTheDocument();
  });

  it('given a long search on the results, then no word count adds a hint: the box offers Ask the guide as always', async () => {
    const m = mockApi();
    renderApp('/search?q=summarise+my+meeting+notes+quickly');
    await (await results()).findByText('Acme Translator');
    expect(screen.queryByText(/Sounds like a question/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Ask the guide' })).toBeInTheDocument();
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(0);
  });

  it('the results offer no second Ask the guide: the box has it; an empty result keeps "Ask the guide instead"', async () => {
    mockApi({ 'GET /search': () => makeSearch({ results: [], total: 0 }) });
    renderApp('/search?q=translate');
    expect(
      await screen.findByRole('button', { name: 'Ask the guide instead' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Ask the guide about/ })).not.toBeInTheDocument();
  });

  it('typing then asking at once keeps the question: a pending live search must not drop `ask`', async () => {
    const m = mockApi();
    const { params } = renderApp('/');
    await userEvent.type(
      screen.getByRole('textbox', { name: 'Search AI resources' }),
      'mcp server for slack',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Ask the guide' }));
    await waitFor(() => expect(params().get('ask')).toBe('mcp server for slack'));
    await new Promise((r) => setTimeout(r, 1000)); // past the 700 ms live-search delay
    expect(params().get('ask')).toBe('mcp server for slack');
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(1);
  });

  it('new words in the box drop the old question, so the picks never answer something else', async () => {
    mockApi();
    const { params } = renderApp(
      '/search?q=translate+my+newsletter+for+free&ask=translate+my+newsletter+for+free',
    );
    await (await results()).findByText('Acme Translator');
    await userEvent.clear(screen.getByRole('textbox', { name: 'Search AI resources' }));
    await userEvent.type(
      screen.getByRole('textbox', { name: 'Search AI resources' }),
      'postgres mcp',
    );
    await waitFor(() => expect(params().get('q')).toBe('postgres mcp'), { timeout: 2000 });
    expect(params().get('ask')).toBeNull();
    await settle();
  });

  it('live search does not rewrite the box: a trailing space survives the URL update', async () => {
    mockApi();
    const { params } = renderApp('/search?q=mcp');
    await (await results()).findByText('Acme Translator');
    await userEvent.clear(screen.getByRole('textbox', { name: 'Search AI resources' }));
    await userEvent.type(
      screen.getByRole('textbox', { name: 'Search AI resources' }),
      'rag library ',
    );
    await waitFor(() => expect(params().get('q')).toBe('rag library'), { timeout: 2000 });
    expect(screen.getByRole('textbox', { name: 'Search AI resources' })).toHaveValue(
      'rag library ',
    );
  });

  it('a question longer than a search keeps its full text in the box; the search takes its first 200 characters', async () => {
    const m = mockApi();
    const { params } = renderApp('/');
    const question = `an MCP server ${'that works with Postgres '.repeat(12)}`
      .slice(0, 300)
      .trimEnd();
    expect(question.length).toBeGreaterThan(290);
    await userEvent.click(screen.getByRole('textbox', { name: 'Search AI resources' }));
    await userEvent.paste(question);
    await userEvent.click(screen.getByRole('button', { name: 'Ask the guide' }));
    await waitFor(() => expect(params().get('ask')).toBe(question));
    expect(params().get('q')).toBe(question.slice(0, 200).trimEnd());
    await new Promise((r) => setTimeout(r, 1000)); // past the live-search delay
    expect(screen.getByRole('textbox', { name: 'Search AI resources' })).toHaveValue(question);
    expect(m.callsTo('POST', '/guide/stream')[0]?.body).toEqual({ request: question });
  });

  it('back and forward put the URL query back in the box', async () => {
    mockApi();
    const { router } = renderApp('/?q=first');
    await userEvent.clear(screen.getByRole('textbox', { name: 'Search AI resources' }));
    await userEvent.type(
      screen.getByRole('textbox', { name: 'Search AI resources' }),
      'second{Enter}',
    );
    await waitFor(() => expect(router.state.location.search).toBe('?q=second'));
    await act(() => router.navigate(-1));
    await waitFor(() =>
      expect(screen.getByRole('textbox', { name: 'Search AI resources' })).toHaveValue('first'),
    );
    await settle();
  });

  it('clearing the box and pressing Enter returns to the empty home', async () => {
    mockApi();
    const { router } = renderApp('/search?q=translate&type=MODEL');
    await (await results()).findByText('Acme Translator');
    await userEvent.clear(screen.getByRole('textbox', { name: 'Search AI resources' }));
    await userEvent.type(screen.getByRole('textbox', { name: 'Search AI resources' }), '{Enter}');
    await waitFor(() => expect(router.state.location.search).toBe(''));
    expect(await screen.findByRole('heading', { name: 'Try:' })).toBeInTheDocument();
  });

  it('the search and the Sources page share one GET /sources', async () => {
    const m = mockApi();
    const { router } = renderApp('/search?q=translate');
    await (await results()).findByText('Acme Translator');
    await act(() => router.navigate('/sources'));
    await screen.findByRole('heading', { level: 1, name: /Sources/ });
    expect(m.callsTo('GET', '/sources')).toHaveLength(1);
    await settle();
  });

  it('the loading feeds nest no status role inside their live regions', async () => {
    mockApi({
      'GET /recent': () => new Promise(() => undefined),
      'GET /events/upcoming': () => new Promise(() => undefined),
    });
    renderApp('/');
    expect(await screen.findByRole('heading', { name: /New this week/ })).toBeInTheDocument();
    expect(document.querySelectorAll('[aria-busy="true"]').length).toBeGreaterThanOrEqual(2);
    expect(document.querySelectorAll('[aria-live] [role="status"]')).toHaveLength(0);
  });
});
