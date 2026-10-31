import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axeViolations } from '@/test/a11y';
import { known, makeCard, makeDetail, makeSearch, unknownDate } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp } from '@/test/render';
import type { Evidence, Relationship, SetupGuide, ToolGuide } from '@/api/types';

const ID = '11111111-1111-5111-8111-111111111111';
const PAGE = `/resource/${ID}`;
const ev: Evidence = { id: '44444444-4444-5444-8444-444444444444', sourceUrl: 'https://github.com/acme/translator/README.md', retrievedAt: '2026-10-20T10:00:00Z', excerpt: 'Supports 40 languages.' };
const rel = (title: string, verification: Relationship['verification']): Relationship => ({ kind: 'INTEGRATES_WITH', targetTitle: title, targetId: null, verification, evidence: [ev] });
const part = (name: string | RegExp) => screen.getByRole('heading', { level: 2, name }).closest('section') as HTMLElement;
const title = (name = 'Acme Translator') => screen.findByRole('heading', { level: 1, name });

describe('the resource page: what it shows', () => {
  it('given a resource, it shows the parts the preview shows: type, site, title, actions, description, facts, listed by, timeline', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail() });
    const { container } = renderApp(PAGE);
    expect(await title()).toBeInTheDocument();
    await waitFor(() => expect(document.title).toBe('Acme Translator · Helper2.ai'));
    for (const label of ['Description', 'Facts', 'Listed by', 'Timeline']) expect(screen.getByRole('heading', { level: 2, name: label })).toBeInTheDocument();
    expect(screen.getByText('Translates documents between languages.')).toBeInTheDocument();
    expect(within(part('Facts')).getByText('Use')).toBeInTheDocument();
    expect(within(part('Facts')).getByText('Build')).toBeInTheDocument();
    expect(within(part('Listed by')).getByRole('link', { name: /GitHub/ })).toHaveAttribute('href', 'https://github.com/acme/translator');
    // From the results the preview links here; the page itself does not link to itself.
    expect(screen.queryByRole('link', { name: /Full details/ })).not.toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('Open on source is a safe external link', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail() });
    renderApp(PAGE);
    const link = await screen.findByRole('link', { name: /Open on source/ });
    expect(link).toHaveAttribute('href', 'https://www.acme.example/translator');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer nofollow');
  });

  it('a javascript: address is shown as text, never as a link', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail({ card: makeCard({ url: 'javascript:alert(1)' }) }) });
    renderApp(PAGE);
    await title();
    expect(screen.queryByRole('link', { name: /Open on source/ })).not.toBeInTheDocument();
  });

  it('dates are the source\'s own or the word Unknown, and disagreeing sources are each named', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail({ card: makeCard({ timeline: {
      published: { status: 'CONFLICTING', precision: 'INSTANT', value: '2025-01-01T00:00:00Z', sourceId: 'github', alternatives: [{ value: '2025-02-02T00:00:00Z', sourceId: 'hf' }] },
      updated: known('2026-09-01T00:00:00Z'),
      discovered: unknownDate,
    } }) }) });
    renderApp(PAGE);
    await title();
    const dates = within(part('Timeline')).getByRole('group', { name: 'Dates' });
    expect(within(dates).getByText('Last updated').nextElementSibling).toHaveTextContent('Sep 1, 2026');
    expect(within(dates).getByText('Discovered by Helper2').nextElementSibling).toHaveTextContent('Unknown');
    expect(within(dates).getByText('Sources disagree')).toBeInTheDocument();
    expect(within(dates).getByText('(source: hf)')).toBeInTheDocument();
  });

  it('shows the tags, and what the type means on Enter, with how the type was assigned', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail({ card: makeCard({ type: 'MCP_SERVER' }) }) });
    renderApp(PAGE);
    await title();
    expect(within(screen.getByRole('list', { name: 'Tags' })).getByText('translation')).toBeInTheDocument();
    const term = within(part('Description')).getByRole('button', { name: /MCP server/ });
    term.focus();
    await userEvent.keyboard('{Enter}');
    expect(term).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('note')).toHaveTextContent('Helper2 assigns the type');
  });
});

describe('the resource page: AI-written text and evidence (ADR 0038)', () => {
  it('summary claims are labelled and marked as AI-written by their model, with citations on demand', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail({ enrichmentStatus: 'VALIDATED', summary: { claims: [{ text: 'It translates PDFs.', evidence: [ev] }], model: 'm1', promptVersion: 'v3', generatedAt: '2026-10-21T00:00:00Z' } }) });
    renderApp(PAGE);
    await title();
    const summary = part('AI summary');
    const claim = within(summary).getByText('It translates PDFs.');
    expect(claim).toHaveAttribute('data-ai-generated', 'true');
    expect(claim).toHaveAttribute('data-ai-model', 'm1');
    expect(within(summary).getByText(/Generated by m1/)).toBeInTheDocument();
    const cites = within(summary).getByText('1 citation');
    expect(within(summary).getByText('Supports 40 languages.')).not.toBeVisible();
    await userEvent.click(cites);
    expect(within(summary).getByText('Supports 40 languages.')).toBeVisible();
  });

  it('without a summary it says why, from the enrichment status', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail({ enrichmentStatus: 'LLM_UNAVAILABLE' }) });
    renderApp(PAGE);
    await title();
    expect(within(part('AI summary')).getByText('No AI summary')).toBeInTheDocument();
    expect(within(part('AI summary')).getByText(/language model was unavailable/)).toBeInTheDocument();
  });

  it('what the sources say: each value with its origin, verification and evidence; only AI-extracted values are marked', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail({ assertions: [
      { field: 'license', value: 'MIT', origin: 'AI_EXTRACTED', verification: 'VERIFIED', sourceId: 'github', evidence: [ev] },
      { field: 'stars', value: '120', origin: 'SOURCE_PROVIDED', verification: 'UNVERIFIED', sourceId: 'github', evidence: [] },
    ] }) });
    renderApp(PAGE);
    await title();
    const says = within(part('What the sources say')).getAllByRole('listitem').filter((li) => li.classList.contains('resource-say'));
    const [license, stars] = says as [HTMLElement, HTMLElement];
    expect(within(license).getByText('MIT')).toHaveAttribute('data-ai-generated', 'true');
    expect(within(license).getByText('Extracted by AI')).toBeInTheDocument();
    expect(within(license).getByText('Verified')).toBeInTheDocument();
    expect(within(license).getByText('Supports 40 languages.')).toBeInTheDocument();
    expect(within(license).getByText(/Retrieved Oct 20, 2026/)).toBeInTheDocument();
    expect(within(stars).getByText('120')).not.toHaveAttribute('data-ai-generated');
    expect(within(stars).getByText('Unverified')).toBeInTheDocument();
    expect(within(stars).getByText('No evidence attached.')).toBeInTheDocument();
  });

  it('keeps verified relationships apart from unverified candidates and never shows rejected ones', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail({ relationships: [rel('Slack', 'VERIFIED'), rel('Notion', 'UNVERIFIED'), rel('Bogus', 'REJECTED')] }) });
    renderApp(PAGE);
    await title();
    const verified = part('Verified relationships');
    const cand = part(/Unverified candidates/);
    expect(within(verified).getByText('Slack')).toBeInTheDocument();
    expect(within(verified).queryByText('Notion')).not.toBeInTheDocument();
    expect(within(cand).getByText('Notion')).toBeInTheDocument();
    expect(within(cand).getByText(/suggested automatically/)).toBeInTheDocument();
    expect(screen.queryByText('Bogus')).not.toBeInTheDocument();
  });

  it('with no relationships it says so and leaves out the candidates', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail() });
    renderApp(PAGE);
    await title();
    expect(within(part('Verified relationships')).getByText('No verified relationships.')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: /Unverified candidates/ })).not.toBeInTheDocument();
  });

  it('lists the source records: the record, when it was read, its fingerprint', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail() });
    renderApp(PAGE);
    await title();
    const records = part('Source records (provenance)');
    expect(within(records).getByRole('link', { name: /acme\/translator/ })).toHaveAttribute('href', 'https://github.com/acme/translator');
    expect(within(records).getByText('abcdef012345')).toBeInTheDocument();
  });
});

describe('the resource page: Guide me (ADR 0031)', () => {
  const SETUP: SetupGuide = {
    resourceId: ID, title: 'pg-mcp-server', version: '1.2.0',
    options: [
      { id: 'o1', kind: 'REMOTE', transport: 'STREAMABLE_HTTP', label: 'Hosted server (Streamable HTTP)', url: 'https://mcp.acme.dev/mcp', settings: [] },
      { id: 'o2', kind: 'PACKAGE', transport: 'STDIO', label: 'npm package @acme/pg-mcp', registryType: 'npm', identifier: '@acme/pg-mcp', runtime: 'npx', requires: 'Node.js (npx)',
        settings: [{ name: 'DATABASE_URL', kind: 'ENV', description: 'Connection string', required: true, secret: true }] },
    ],
    clients: [
      { id: 'claude-code', name: 'Claude Code', docsUrl: 'https://code.claude.com/docs/en/mcp', snippets: [
        { optionId: 'o1', supported: true, where: 'Terminal', language: 'shell', text: 'claude mcp add --transport http pg-mcp-server https://mcp.acme.dev/mcp' },
        { optionId: 'o2', supported: true, where: 'Terminal', language: 'shell', text: "claude mcp add --env 'DATABASE_URL=<DATABASE_URL>' --transport stdio pg-mcp-server -- npx -y @acme/pg-mcp@1.2.0" },
      ] },
      { id: 'goose', name: 'Goose', docsUrl: 'https://goose-docs.ai/docs/guides/config-files', snippets: [
        { optionId: 'o1', supported: true, where: '~/.config/goose/config.yaml', language: 'yaml', text: 'extensions:' },
        { optionId: 'o2', supported: false, note: 'This client\'s documentation does not describe a way to configure this.' },
      ] },
    ],
    sources: [{ sourceId: 'mcp-registry', url: 'https://registry.modelcontextprotocol.io/v0.1/servers/io.acme%2Fpg/versions/latest', retrievedAt: '2026-10-27T20:00:00Z' }],
  };

  const STEPS: ToolGuide = {
    resourceId: ID, title: 'pg-mcp-server', status: 'OK', question: null, rejected: 1, generatedAt: '2026-10-28T00:00:00Z', model: 'nemotron-nano',
    sections: [
      { kind: 'REQUIREMENTS', steps: [{ text: 'Install Node.js 20 or later.', quote: 'Requires Node.js 20 or later.', source: 'readme', caution: false }] },
      { kind: 'INSTALL', steps: [{ text: 'Install it with npm.', quote: 'Install globally:', source: 'readme', caution: false,
        code: { language: 'bash', text: 'npm install -g @acme/pg-mcp' } }] },
    ],
    documents: [{ key: 'readme', kind: 'README', url: 'https://github.com/acme/pg-mcp', retrievedAt: '2026-10-28T00:00:00Z' }],
  };
  const tool = (type: 'MCP_SERVER' | 'LIBRARY' | 'PAPER', name: string) => () => makeDetail({ card: makeCard({ type, title: name }) });

  it('opens under the actions with focus on its heading: snippets per AI tool, checked steps and a follow-up box; closing returns focus', async () => {
    const m = mockApi({
      [`GET /resources/${ID}`]: tool('MCP_SERVER', 'pg-mcp-server'),
      [`GET /resources/${ID}/setup`]: () => SETUP,
      [`POST /resources/${ID}/guide`]: ({ body }) => (body as { question?: string }).question
        ? { ...STEPS, question: 'Does it need an API key?', sections: [{ kind: 'ANSWER', steps: [{ text: 'No key; it needs a database URL.', quote: 'Pass the database URL', source: 'readme', caution: false }] }] }
        : STEPS,
    });
    renderApp(PAGE);
    const button = await screen.findByRole('button', { name: 'Guide me: pg-mcp-server' });
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(m.callsTo('GET', `/resources/${ID}/setup`)).toHaveLength(0);
    button.focus();
    await userEvent.keyboard('{Enter}');

    const heading = await screen.findByRole('heading', { level: 2, name: 'Set up pg-mcp-server' });
    await waitFor(() => expect(heading).toHaveFocus());
    expect(await screen.findByText('claude mcp add --transport http pg-mcp-server https://mcp.acme.dev/mcp')).toBeInTheDocument();
    expect(screen.getByText(/From its Official MCP Registry entry, version 1.2.0/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('radio', { name: 'npm package @acme/pg-mcp' }));
    expect(screen.getByText(/needs Node.js \(npx\)/)).toBeInTheDocument();
    expect(screen.getByText(/secret: fill in your own/)).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText('Your AI tool'), 'goose');
    expect(screen.getByText(/Not available in Goose/)).toBeInTheDocument();

    expect(await screen.findByRole('heading', { name: 'Install' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'What you need first' })).toBeInTheDocument();
    expect(screen.getByText('npm install -g @acme/pg-mcp')).toBeInTheDocument();
    expect(screen.getByText(/copied exactly from it; commands are not reviewed for safety/)).toBeInTheDocument();
    expect(m.callsTo('POST', `/resources/${ID}/guide`)[0]?.body).toEqual({});

    await userEvent.type(screen.getByLabelText('Ask about pg-mcp-server'), 'Does it need an API key?');
    await userEvent.click(screen.getByRole('button', { name: 'Ask' }));
    expect(await screen.findByText('No key; it needs a database URL.')).toBeInTheDocument();
    expect(m.callsTo('POST', `/resources/${ID}/guide`)[1]?.body).toEqual({ question: 'Does it need an API key?' });

    await userEvent.click(button);
    expect(screen.queryByRole('heading', { name: 'Set up pg-mcp-server' })).not.toBeInTheDocument();
    expect(button).toHaveFocus();
  });

  it('a run command copied from the README says it is not reviewed for safety', async () => {
    mockApi({
      [`GET /resources/${ID}`]: tool('MCP_SERVER', 'pg-mcp-server'),
      [`GET /resources/${ID}/setup`]: () => ({
        ...SETUP,
        options: [{ id: 'o1', kind: 'COMMAND', transport: 'STDIO', label: 'npx @acme/pg-mcp', runtime: 'npx', requires: 'Node.js (npx)', settings: [] }],
        clients: [{ id: 'claude-code', name: 'Claude Code', docsUrl: 'https://code.claude.com/docs/en/mcp', snippets: [
          { optionId: 'o1', supported: true, where: 'Terminal', language: 'shell', text: 'claude mcp add --transport stdio pg-mcp-server -- npx -y @acme/pg-mcp' },
        ] }],
        sources: [{ sourceId: 'readme', url: 'https://github.com/acme/pg-mcp', retrievedAt: '2026-10-27T20:00:00Z' }],
      }),
      [`POST /resources/${ID}/guide`]: () => STEPS,
    });
    renderApp(PAGE);
    await userEvent.click(await screen.findByRole('button', { name: 'Guide me: pg-mcp-server' }));
    expect(await screen.findByText(/Runs locally with npx, copied from its README; not reviewed for safety/)).toBeInTheDocument();
  });

  it('without setup data or documents it says why, and the follow-up box stays', async () => {
    mockApi({
      [`GET /resources/${ID}`]: tool('LIBRARY', 'acme-lib'),
      [`GET /resources/${ID}/setup`]: () => ({ resourceId: ID, title: 'acme-lib', version: null, options: [], clients: [], sources: [] }),
      [`POST /resources/${ID}/guide`]: () => ({ ...STEPS, title: 'acme-lib', status: 'NO_MATERIAL', sections: [], documents: [], model: null, rejected: 0 }),
    });
    renderApp(PAGE);
    await userEvent.click(await screen.findByRole('button', { name: 'Guide me: acme-lib' }));
    expect(await screen.findByText(/acme-lib has no README or model card/)).toBeInTheDocument();
    expect(screen.queryByLabelText('Your AI tool')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Ask about acme-lib')).toBeInTheDocument();
  });

  it('offers no Guide me on a paper', async () => {
    mockApi({ [`GET /resources/${ID}`]: tool('PAPER', 'A survey') });
    renderApp(PAGE);
    await title('A survey');
    expect(screen.queryByRole('button', { name: /Guide me/ })).not.toBeInTheDocument();
  });
});

describe('the resource page: coming and going', () => {
  it('from the results, Back to results returns to the same search and selection', async () => {
    const B = '22222222-2222-5222-8222-222222222222';
    mockApi({
      'GET /search': () => makeSearch({ results: [makeCard(), makeCard({ id: B, title: 'Beta library' })], total: 2 }),
      [`GET /resources/${B}`]: () => makeDetail({ card: makeCard({ id: B, title: 'Beta library' }) }),
    });
    const { router } = renderApp('/search?q=agents');
    await userEvent.click(await screen.findByRole('button', { name: /^Beta library / }));
    await userEvent.click(screen.getByRole('link', { name: /Full details/ }));
    await title('Beta library');
    await userEvent.click(screen.getByRole('link', { name: /Back to results/ }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/search'));
    expect(new URLSearchParams(router.state.location.search).get('id')).toBe(B);
    expect(screen.getByRole('button', { name: /^Beta library / })).toHaveAttribute('aria-current', 'true');
  });

  it('opened from a link, it has no Back to results: the header has the box and the way home', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail() });
    renderApp(PAGE);
    await title();
    expect(screen.queryByRole('link', { name: /Back to results/ })).not.toBeInTheDocument();
  });
});

describe('the resource page: designed states (ADR 0040, 8)', () => {
  it('while it loads, it says so', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => new Promise(() => undefined) });
    renderApp(PAGE);
    expect(await screen.findByText('Loading…')).toBeInTheDocument();
  });

  it('a resource not in the index: says so as the page heading, with the way home and no Try again', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => problem(404, 'Not found') });
    const { container } = renderApp(PAGE);
    expect(await screen.findByRole('heading', { level: 1, name: 'Resource not found' })).toBeInTheDocument();
    expect(screen.getByText(/not in the Helper2 index/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to the home page' })).toHaveAttribute('href', '/');
    expect(screen.queryByRole('button', { name: /Try again/ })).not.toBeInTheDocument();
    await waitFor(() => expect(document.title).toBe('Resource not found · Helper2.ai'));
    expect(await axeViolations(container)).toEqual([]);
  });

  it('a network error: an alert with Try again; the reloaded page takes focus on its title', async () => {
    let n = 0;
    mockApi({ [`GET /resources/${ID}`]: () => (n++ === 0 ? problem(503, 'Unavailable') : makeDetail()) });
    const { container } = renderApp(PAGE);
    const alert = await screen.findByRole('alert');
    expect(within(alert).getByRole('heading', { level: 1, name: 'Could not load this resource' })).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    within(alert).getByRole('button', { name: /Try again/ }).focus();
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(screen.getByRole('heading', { level: 1, name: 'Acme Translator' })).toHaveFocus());
  });

  it('still failing after Try again: focus is on the error heading again', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => problem(503, 'Unavailable') });
    renderApp(PAGE);
    await userEvent.click(within(await screen.findByRole('alert')).getByRole('button', { name: /Try again/ }));
    await waitFor(() => expect(screen.getByRole('heading', { level: 1, name: 'Could not load this resource' })).toHaveFocus());
  });
});

describe('the resource page: report wrong or outdated info', () => {
  const open = async () => {
    const m = mockApi({ [`GET /resources/${ID}`]: () => makeDetail(), [`POST /resources/${ID}/reports`]: () => new Response(JSON.stringify({ reportId: '55555555-5555-5555-8555-555555555555' }), { status: 202, headers: { 'content-type': 'application/json' } }) });
    renderApp(PAGE);
    await screen.findByRole('heading', { name: 'Report wrong or outdated info' });
    return m;
  };

  it('checks the message length (5-2000) before sending', async () => {
    const m = await open();
    await userEvent.type(screen.getByLabelText('Describe the problem'), 'abc');
    await userEvent.click(screen.getByRole('button', { name: 'Send report' }));
    expect(screen.getByRole('alert')).toHaveTextContent('between 5 and 2000');
    expect(m.callsTo('POST', `/resources/${ID}/reports`)).toHaveLength(0);
  });

  it('sends the kind, the optional field and the message, and says thank you', async () => {
    const m = await open();
    await userEvent.selectOptions(screen.getByLabelText('What is the problem?'), 'OUTDATED');
    await userEvent.type(screen.getByLabelText(/Which field/), 'license');
    await userEvent.type(screen.getByLabelText('Describe the problem'), 'License changed to Apache.');
    await userEvent.click(screen.getByRole('button', { name: 'Send report' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Thank you');
    expect(m.callsTo('POST', `/resources/${ID}/reports`)[0]?.body).toEqual({ kind: 'OUTDATED', field: 'license', message: 'License changed to Apache.' });
  });

  it('sends the field as null when left empty', async () => {
    const m = await open();
    await userEvent.type(screen.getByLabelText('Describe the problem'), 'Wrong price shown');
    await userEvent.click(screen.getByRole('button', { name: 'Send report' }));
    await waitFor(() => expect(m.callsTo('POST', `/resources/${ID}/reports`)[0]?.body).toMatchObject({ kind: 'WRONG', field: null }));
  });

  it('shows the server\'s reason on failure and lets the user send again', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail(), [`POST /resources/${ID}/reports`]: () => problem(429, 'Too many', 'Too many reports, slow down.') });
    renderApp(PAGE);
    await userEvent.type(await screen.findByLabelText('Describe the problem'), 'Something wrong here');
    await userEvent.click(screen.getByRole('button', { name: 'Send report' }));
    expect(await screen.findByText('Too many reports, slow down.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Send report' })).toBeEnabled();
  });
});
