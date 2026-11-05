import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { GuideResponse, ToolGuide } from '@/api/types';
import { axeViolations } from '@/test/a11y';
import { makeCard, makeDetail, makeSearch, makeStatus } from '@/test/fixtures';
import { mockApi, sse } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

const PG = makeCard({
  id: 'bbbbbbbb-bbbb-5bbb-8bbb-bbbbbbbbbbbb',
  title: 'Postgres MCP',
  type: 'MCP_SERVER',
  url: 'https://github.com/acme/postgres-mcp',
  description:
    'An MCP server that gives agents read-only access to a PostgreSQL database. Easy to set up.',
  license: null,
});

function guide(): GuideResponse {
  return {
    status: 'OK',
    searchQuery: 'postgres mcp',
    candidatesConsidered: 1,
    rejected: 0,
    sources: [],
    notices: [],
    generatedAt: '2026-10-25T10:00:00Z',
    prompt: null,
    model: {
      provider: 'nebius-token-factory',
      fastModel: 'f',
      reasoningModel: 'nvidia/nemotron-3-super',
    },
    recommendations: [
      {
        resourceId: PG.id,
        title: PG.title,
        url: PG.url,
        type: 'MCP_SERVER',
        reason: 'Read-only access fits an agent that only reads.',
        quote: 'gives agents read-only access to a PostgreSQL database',
        sourceIds: ['github'],
        aiReasoned: true,
        reasonCheck: 'NOT_CHECKED',
      },
    ],
  };
}

const STEPS: ToolGuide = {
  resourceId: PG.id,
  title: PG.title,
  status: 'OK',
  rejected: 0,
  generatedAt: '2026-10-28T00:00:00Z',
  model: 'nemotron-nano',
  sections: [
    {
      kind: 'INSTALL',
      steps: [
        {
          text: 'Install it with npm.',
          quote: 'Install globally:',
          source: 'readme',
          caution: false,
        },
      ],
    },
  ],
  documents: [
    {
      key: 'readme',
      kind: 'README',
      url: 'https://github.com/acme/postgres-mcp',
      retrievedAt: '2026-10-28T00:00:00Z',
    },
  ],
};

describe('the resource page', () => {
  it('opened from a guide pick: shows the checked sentence highlighted, and the back link returns to the same search and result', async () => {
    mockApi({
      'GET /search': () => makeSearch({ results: [PG], total: 1, sources: [makeStatus()] }),
      'POST /guide/stream': () => sse(guide()),
      [`GET /resources/${PG.id}`]: () => makeDetail({ card: PG }),
    });
    const { params, router } = renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    await screen.findByRole('button', { name: '#1 Postgres MCP' });
    await userEvent.click(
      within(screen.getByRole('article')).getByRole('link', { name: 'Postgres MCP' }),
    );

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Postgres MCP' }),
    ).toBeInTheDocument();
    expect(screen.getByText('gives agents read-only access to a PostgreSQL database').tagName).toBe(
      'MARK',
    );
    const back = screen.getByRole('link', { name: /Back to results/ });
    await userEvent.click(back);
    expect(router.state.location.pathname).toBe('/search');
    expect(params().get('q')).toBe('postgres mcp');
    expect(params().get('id')).toBe(PG.id);
  });

  it('a checked quote from the title alone is still shown when there is no description', async () => {
    const bare = { ...PG, description: null };
    const g = guide();
    g.recommendations = [{ ...g.recommendations[0]!, quote: 'Postgres MCP' }];
    mockApi({
      'GET /search': () => makeSearch({ results: [bare], total: 1, sources: [makeStatus()] }),
      'POST /guide/stream': () => sse(g),
      [`GET /resources/${PG.id}`]: () => makeDetail({ card: bare }),
    });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    await screen.findByRole('button', { name: '#1 Postgres MCP' });
    await userEvent.click(
      within(screen.getByRole('article')).getByRole('link', { name: 'Postgres MCP' }),
    );
    await screen.findByRole('heading', { level: 1, name: 'Postgres MCP' });
    expect(document.querySelector('.h2-proof mark')).toHaveTextContent('Postgres MCP');
    expect(screen.queryByText('No description available.')).not.toBeInTheDocument();
  });

  it('an AI-written description is labelled and marked, and never headed "From the source" (ADR 0038)', async () => {
    const ai = { ...PG, description: 'A short summary.', descriptionOrigin: 'AI_SUMMARY' as const };
    mockApi({ [`GET /resources/${PG.id}`]: () => makeDetail({ card: ai }) });
    renderApp(`/resource/${PG.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Postgres MCP' });
    expect(screen.getByRole('heading', { name: 'About' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'From the source' })).not.toBeInTheDocument();
    const text = screen.getByText('A short summary.');
    expect(text).toHaveAttribute('data-ai-generated', 'true');
    expect(text.previousElementSibling).toHaveTextContent('AI summary');
  });

  it('an AI-written description is not shown as the checked quote of a pick', async () => {
    const ai = { ...PG, descriptionOrigin: 'AI_EXTRACTED' as const };
    mockApi({
      'GET /search': () => makeSearch({ results: [ai], total: 1, sources: [makeStatus()] }),
      'POST /guide/stream': () => sse(guide()),
      [`GET /resources/${PG.id}`]: () => makeDetail({ card: ai }),
    });
    renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    await screen.findByRole('button', { name: '#1 Postgres MCP' });
    await userEvent.click(
      within(screen.getByRole('article')).getByRole('link', { name: 'Postgres MCP' }),
    );
    await screen.findByRole('heading', { level: 1, name: 'Postgres MCP' });
    expect(screen.getByText(/read-only access to a PostgreSQL database/)).toHaveAttribute(
      'data-ai-generated',
      'true',
    );
    expect(screen.getByText('Extracted by AI')).toBeInTheDocument();
    expect(document.querySelector('.h2-res .h2-proof')).toBeNull();
  });

  it('the timeline shows every date: published, updated and first seen', async () => {
    mockApi({ [`GET /resources/${PG.id}`]: () => makeDetail({ card: PG }) });
    renderApp(`/resource/${PG.id}`);
    const time = (await screen.findByRole('heading', { level: 2, name: /Timeline/i })).closest(
      'section',
    ) as HTMLElement;
    expect(time).toHaveTextContent('Published');
    expect(time).toHaveTextContent('Discovered by Helper2');
    await settle();
  });

  it('opened directly: the source text without a highlight, facts no source states say Not stated, back goes to search', async () => {
    mockApi({ [`GET /resources/${PG.id}`]: () => makeDetail({ card: PG }) });
    renderApp(`/resource/${PG.id}`);
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Postgres MCP' }),
    ).toBeInTheDocument();
    expect(screen.getByText(PG.description!)).toBeInTheDocument();
    expect(document.querySelector('mark')).toBeNull();
    const facts = screen.getByRole('complementary', { name: 'Facts' });
    expect(within(facts).getAllByText('Not stated').length).toBeGreaterThan(0);
    expect(within(facts).getByText('GitHub')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Back to search/ })).toHaveAttribute('href', '/');
    expect(screen.getByRole('link', { name: /Open on github.com/ })).toHaveAttribute(
      'href',
      PG.url,
    );
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });

  it('Build facts say what it works with, and why, from the disclosed rules', async () => {
    const card = {
      ...PG,
      compatibleWith: [
        {
          tool: 'mcp',
          label: 'Any MCP client',
          basis: 'TYPE' as const,
          evidence: 'MCP_SERVER',
          sourceId: null,
        },
        {
          tool: 'ollama',
          label: 'Ollama',
          basis: 'TAG' as const,
          evidence: 'ollama',
          sourceId: 'github',
        },
      ],
    };
    mockApi({ [`GET /resources/${PG.id}`]: () => makeDetail({ card }) });
    renderApp(`/resource/${PG.id}`);
    const facts = await screen.findByRole('complementary', { name: 'Facts' });
    expect(within(facts).getByText('Works with')).toBeInTheDocument();
    expect(within(facts).getByText('Ollama').closest('li')).toHaveAttribute(
      'title',
      'github lists the tag “ollama”',
    );
    expect(within(facts).getByText(/MCP servers work with any MCP client/)).toBeInTheDocument();
  });

  it('the setup steps written by AI are marked machine-readable', async () => {
    mockApi({
      [`GET /resources/${PG.id}`]: () => makeDetail({ card: PG }),
      [`GET /resources/${PG.id}/setup`]: () => ({
        resourceId: PG.id,
        title: PG.title,
        version: null,
        options: [],
        clients: [],
        sources: [],
      }),
      [`POST /resources/${PG.id}/guide`]: () => STEPS,
    });
    renderApp(`/resource/${PG.id}`);
    await userEvent.click(await screen.findByRole('button', { name: 'Guide me' }));
    const step = await screen.findByText('Install it with npm.');
    expect(step).toHaveAttribute('data-ai-generated', 'true');
    expect(step).toHaveAttribute('data-ai-model', 'nemotron-nano');
    expect(screen.getByText(/Steps written by AI/)).toBeInTheDocument();
  });

  it('has no axe violations', async () => {
    mockApi({ [`GET /resources/${PG.id}`]: () => makeDetail({ card: PG }) });
    const { container } = renderApp(`/resource/${PG.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Postgres MCP' });
    await settle();
    expect(await axeViolations(container)).toEqual([]);
  });
});
