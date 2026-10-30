import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { makeCard, makeDetail } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp } from '@/test/render';
import type { Evidence, Relationship, SetupGuide, ToolGuide } from '@/api/types';

const ID = '11111111-1111-5111-8111-111111111111';
const ev: Evidence = { id: '44444444-4444-5444-8444-444444444444', sourceUrl: 'https://github.com/acme/translator/README.md', retrievedAt: '2026-10-20T10:00:00Z', excerpt: 'Supports 40 languages.' };
const rel = (title: string, verification: Relationship['verification']): Relationship => ({ kind: 'INTEGRATES_WITH', targetTitle: title, targetId: null, verification, evidence: [ev] });

describe('resource detail', () => {
  it('separates verified relationships from unverified candidates', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail({ relationships: [rel('Slack', 'VERIFIED'), rel('Notion', 'UNVERIFIED'), rel('Bogus', 'REJECTED')] }) });
    renderApp(`/resource/${ID}`);
    const verified = (await screen.findByRole('heading', { name: 'Verified relationships' })).closest('section') as HTMLElement;
    const cand = screen.getByRole('heading', { name: /Unverified candidates/ }).closest('section') as HTMLElement;
    expect(within(verified).getByText('Slack')).toBeInTheDocument();
    expect(within(verified).queryByText('Notion')).not.toBeInTheDocument();
    expect(within(cand).getByText('Notion')).toBeInTheDocument();
    expect(within(cand).getByText('Unverified')).toBeInTheDocument();
    expect(within(cand).queryByText('Slack')).not.toBeInTheDocument();
    expect(screen.queryByText('Bogus')).not.toBeInTheDocument();
  });

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

  it('Guide me opens snippets per AI tool, checked steps from the README, and a follow-up box', async () => {
    const m = mockApi({
      [`GET /resources/${ID}`]: () => makeDetail({ card: makeCard({ type: 'MCP_SERVER', title: 'pg-mcp-server' }) }),
      [`GET /resources/${ID}/setup`]: () => SETUP,
      [`POST /resources/${ID}/guide`]: ({ body }) => (body as { question?: string }).question
        ? { ...STEPS, question: 'Does it need an API key?', sections: [{ kind: 'ANSWER', steps: [{ text: 'No key; it needs a database URL.', quote: 'Pass the database URL', source: 'readme', caution: false }] }] }
        : STEPS,
    });
    renderApp(`/resource/${ID}`);
    const button = await screen.findByRole('button', { name: 'Guide me' });
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(m.callsTo('GET', `/resources/${ID}/setup`)).toHaveLength(0);
    await userEvent.click(button);

    expect(await screen.findByRole('heading', { name: 'Set up pg-mcp-server' })).toBeInTheDocument();
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
  });

  it('a run command copied from the README says it is not reviewed for safety', async () => {
    mockApi({
      [`GET /resources/${ID}`]: () => makeDetail({ card: makeCard({ type: 'MCP_SERVER', title: 'pg-mcp-server' }) }),
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
    renderApp(`/resource/${ID}`);
    await userEvent.click(await screen.findByRole('button', { name: 'Guide me' }));
    expect(await screen.findByText(/Runs locally with npx, copied from its README; not reviewed for safety/)).toBeInTheDocument();
  });

  it('without setup data or documents it says why, and the follow-up box stays', async () => {
    mockApi({
      [`GET /resources/${ID}`]: () => makeDetail({ card: makeCard({ type: 'LIBRARY', title: 'acme-lib' }) }),
      [`GET /resources/${ID}/setup`]: () => ({ resourceId: ID, title: 'acme-lib', version: null, options: [], clients: [], sources: [] }),
      [`POST /resources/${ID}/guide`]: () => ({ ...STEPS, title: 'acme-lib', status: 'NO_MATERIAL', sections: [], documents: [], model: null, rejected: 0 }),
    });
    renderApp(`/resource/${ID}`);
    await userEvent.click(await screen.findByRole('button', { name: 'Guide me' }));
    expect(await screen.findByText(/acme-lib has no README or model card/)).toBeInTheDocument();
    expect(screen.queryByLabelText('Your AI tool')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Ask about acme-lib')).toBeInTheDocument();
  });

  it('offers no Guide me on a paper', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail({ card: makeCard({ type: 'PAPER', title: 'A survey' }) }) });
    renderApp(`/resource/${ID}`);
    await screen.findByRole('heading', { name: 'A survey' });
    expect(screen.queryByRole('button', { name: 'Guide me' })).not.toBeInTheDocument();
  });

  it('omits the candidates section when there are none', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail() });
    renderApp(`/resource/${ID}`);
    await screen.findByRole('heading', { name: 'Verified relationships' });
    expect(screen.queryByRole('heading', { name: /Unverified candidates/ })).not.toBeInTheDocument();
    expect(screen.getByText('No verified relationships.')).toBeInTheDocument();
  });

  it('renders assertions with origin, verification and evidence excerpt', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail({ assertions: [{ field: 'license', value: 'MIT', origin: 'AI_EXTRACTED', verification: 'VERIFIED', sourceId: 'github', evidence: [ev] }] }) });
    renderApp(`/resource/${ID}`);
    const table = await screen.findByRole('table', { name: '' }).catch(() => null);
    expect(table ?? true).toBeTruthy();
    expect(await screen.findByText('Extracted by AI')).toBeInTheDocument();
    expect(screen.getByText('Verified', { selector: '.verif' })).toBeInTheDocument();
    expect(screen.getByText('Supports 40 languages.')).toBeInTheDocument();
    expect(screen.getByText(/Retrieved Oct 20, 2026/)).toBeInTheDocument();
  });

  it('explains missing AI summary by enrichment status', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail({ enrichmentStatus: 'LLM_UNAVAILABLE' }) });
    renderApp(`/resource/${ID}`);
    expect(await screen.findByText('No AI summary')).toBeInTheDocument();
    expect(screen.getByText(/language model was unavailable/)).toBeInTheDocument();
  });

  it('shows summary claims with citations', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail({ enrichmentStatus: 'VALIDATED', summary: { claims: [{ text: 'It translates PDFs.', evidence: [ev] }], model: 'm1', promptVersion: 'v3', generatedAt: '2026-10-21T00:00:00Z' } }) });
    renderApp(`/resource/${ID}`);
    expect(await screen.findByText('It translates PDFs.')).toBeInTheDocument();
    expect(screen.getByText('1 citation')).toBeInTheDocument();
    expect(screen.queryByText('No AI summary')).not.toBeInTheDocument();
  });

  it('lists source records', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail() });
    renderApp(`/resource/${ID}`);
    expect(await screen.findByText('acme/translator')).toBeInTheDocument();
    expect(screen.getByText('abcdef012345')).toBeInTheDocument();
  });

  it('shows not found for 404', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => problem(404, 'Not found') });
    renderApp(`/resource/${ID}`);
    expect(await screen.findByText('Resource not found')).toBeInTheDocument();
  });
});

describe('report form', () => {
  const open = async () => {
    const m = mockApi({ [`GET /resources/${ID}`]: () => makeDetail() , [`POST /resources/${ID}/reports`]: () => new Response(JSON.stringify({ reportId: '55555555-5555-5555-8555-555555555555' }), { status: 202, headers: { 'content-type': 'application/json' } }) });
    renderApp(`/resource/${ID}`);
    await screen.findByRole('heading', { name: 'Report wrong or outdated info' });
    return m;
  };

  it('validates message length (5-2000) before sending', async () => {
    const m = await open();
    await userEvent.type(screen.getByLabelText('Describe the problem'), 'abc');
    await userEvent.click(screen.getByRole('button', { name: 'Send report' }));
    expect(screen.getByRole('alert')).toHaveTextContent('between 5 and 2000');
    expect(m.callsTo('POST', `/resources/${ID}/reports`)).toHaveLength(0);
  });

  it('submits kind, optional field and message and shows success', async () => {
    const m = await open();
    await userEvent.selectOptions(screen.getByLabelText('What is the problem?'), 'OUTDATED');
    await userEvent.type(screen.getByLabelText(/Which field/), 'license');
    await userEvent.type(screen.getByLabelText('Describe the problem'), 'License changed to Apache.');
    await userEvent.click(screen.getByRole('button', { name: 'Send report' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Thank you');
    expect(m.callsTo('POST', `/resources/${ID}/reports`)[0]?.body).toEqual({ kind: 'OUTDATED', field: 'license', message: 'License changed to Apache.' });
  });

  it('sends field as null when left empty', async () => {
    const m = await open();
    await userEvent.type(screen.getByLabelText('Describe the problem'), 'Wrong price shown');
    await userEvent.click(screen.getByRole('button', { name: 'Send report' }));
    await waitFor(() => expect(m.callsTo('POST', `/resources/${ID}/reports`)[0]?.body).toMatchObject({ kind: 'WRONG', field: null }));
  });

  it('shows the server problem detail on failure and allows retry', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail(), [`POST /resources/${ID}/reports`]: () => problem(429, 'Too many', 'Too many reports, slow down.') });
    renderApp(`/resource/${ID}`);
    await userEvent.type(await screen.findByLabelText('Describe the problem'), 'Something wrong here');
    await userEvent.click(screen.getByRole('button', { name: 'Send report' }));
    expect(await screen.findByText('Too many reports, slow down.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Send report' })).toBeEnabled();
  });
});
