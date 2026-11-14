import { act, screen, waitFor, within } from '@testing-library/react';
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

const rowLink = (name: string) =>
  screen.getAllByRole('link', { name }).find((l) => l.classList.contains('h2-row__title'))!;

describe('the resource page', () => {
  it('opened from a guide pick: shows the checked sentence highlighted, and the back link goes back to the same search', async () => {
    mockApi({
      'GET /search': () => makeSearch({ results: [PG], total: 1, sources: [makeStatus()] }),
      'POST /guide/stream': () => sse(guide()),
      [`GET /resources/${PG.id}`]: () => makeDetail({ card: PG }),
    });
    const { params, router } = renderApp('/search?q=postgres+mcp&ask=postgres+mcp');
    await screen.findByRole('link', { name: '#1 Postgres MCP' });
    await userEvent.click(rowLink('Postgres MCP'));

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
    expect(router.state.historyAction).toBe('POP');
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
    await screen.findByRole('link', { name: '#1 Postgres MCP' });
    await userEvent.click(rowLink('Postgres MCP'));
    await screen.findByRole('heading', { level: 1, name: 'Postgres MCP' });
    expect(document.querySelector('.h2-proof mark')).toHaveTextContent('Postgres MCP');
    expect(screen.queryByText('No description available.')).not.toBeInTheDocument();
  });

  it('an AI-written description is labelled and marked, and never headed "From the source" (ADR 0033)', async () => {
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
    await screen.findByRole('link', { name: '#1 Postgres MCP' });
    await userEvent.click(rowLink('Postgres MCP'));
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
    expect(time).toHaveTextContent('First seen by helper2.ai');
    await settle();
  });

  it('opened directly: the source text without a highlight, one line names the facts no source states, back goes to search', async () => {
    mockApi({ [`GET /resources/${PG.id}`]: () => makeDetail({ card: PG }) });
    renderApp(`/resource/${PG.id}`);
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Postgres MCP' }),
    ).toBeInTheDocument();
    expect(screen.getByText(PG.description!)).toBeInTheDocument();
    expect(document.querySelector('mark')).toBeNull();
    const facts = screen.getByRole('region', { name: 'At a glance' });
    expect(within(facts).queryByText('Not stated')).not.toBeInTheDocument();
    expect(within(facts).getAllByText(/Not stated by any source:/)).toHaveLength(1);
    expect(within(facts).getByText(/Not stated by any source:/)).toHaveTextContent(
      'Not stated by any source: who it is for, license',
    );
    expect(within(facts).queryByText('License')).not.toBeInTheDocument();
    expect(within(facts).getByText('Pricing').nextElementSibling).toHaveTextContent('Freemium');
    expect(screen.getByRole('heading', { level: 1 }).nextElementSibling).toHaveTextContent(
      /^MCP server · github\.com · updated /,
    );
    const where = screen.getByText('Where this information comes from').closest('details')!;
    expect(where).not.toHaveAttribute('open');
    expect(within(where).getByText('GitHub').closest('li')).toHaveTextContent(/Live · read /);
    expect(
      within(where).getByRole('link', { name: 'All sources and their last check' }),
    ).toHaveAttribute('href', '/sources');
    expect(document.querySelectorAll('.h2-res__head .btn-primary')).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Set it up' })).not.toHaveClass('btn-primary');
    expect(screen.getByRole('link', { name: /Back to search/ })).toHaveAttribute('href', '/');
    expect(screen.getByRole('link', { name: /Open on github.com/ })).toHaveAttribute(
      'href',
      PG.url,
    );
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });

  it('opened from a result row: title, Open and Save show at once while the details load', async () => {
    mockApi({
      'GET /search': () => makeSearch({ results: [PG] }),
      [`GET /resources/${PG.id}`]: () => new Promise<Response>(() => {}),
    });
    renderApp('/search?q=postgres+mcp');
    await userEvent.click(await screen.findByRole('link', { name: 'Postgres MCP' }));

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Postgres MCP' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Open on github.com/ })).toHaveAttribute(
      'href',
      PG.url,
    );
    expect(screen.getByRole('button', { name: 'Save Postgres MCP' })).toHaveTextContent('Save');
    expect(screen.getByRole('status')).toHaveTextContent('Loading');
    expect(screen.getByRole('link', { name: /Back to results/ })).toBeInTheDocument();
  });

  it('opened from a result row: focus stays on the title when the details arrive', async () => {
    let arrive: () => void = () => undefined;
    mockApi({
      'GET /search': () => makeSearch({ results: [PG] }),
      [`GET /resources/${PG.id}`]: () =>
        new Promise((resolve) => {
          arrive = () => resolve(makeDetail({ card: PG }));
        }),
    });
    renderApp('/search?q=postgres+mcp');
    await userEvent.click(await screen.findByRole('link', { name: 'Postgres MCP' }));
    const title = await screen.findByRole('heading', { level: 1, name: 'Postgres MCP' });
    await waitFor(() => expect(title).toHaveFocus());

    await act(async () => arrive());

    expect(await screen.findByRole('region', { name: 'At a glance' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Postgres MCP' })).toBe(title);
    expect(title).toHaveFocus();
  });

  it('opened directly while the details load: only the loading line, no guessed header', async () => {
    mockApi({ [`GET /resources/${PG.id}`]: () => new Promise<Response>(() => {}) });
    renderApp(`/resource/${PG.id}`);

    expect(await screen.findByRole('status')).toHaveTextContent('Loading');
    expect(screen.queryByRole('heading', { level: 1 })).toBeNull();
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
    const facts = await screen.findByRole('region', { name: 'At a glance' });
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
    await userEvent.click(await screen.findByRole('button', { name: 'Set it up' }));
    const step = await screen.findByText('Install it with npm.');
    expect(step).toHaveAttribute('data-ai-generated', 'true');
    expect(step).toHaveAttribute('data-ai-model', 'nemotron-nano');
    expect(screen.getByText(/Steps written by AI/)).toBeInTheDocument();
  });

  it('a fact the sources disagree on says so, with each value and its source, not "Not stated"', async () => {
    const split = {
      ...PG,
      conflicts: [
        {
          field: 'license',
          values: [
            { value: 'MIT', sourceId: 'github' },
            { value: 'Apache-2.0', sourceId: 'npm' },
          ],
        },
      ],
    };
    mockApi({ [`GET /resources/${PG.id}`]: () => makeDetail({ card: split }) });
    renderApp(`/resource/${PG.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Postgres MCP' });
    const conflict = document.querySelector('.h2-res dd.is-conflict');
    expect(conflict).toHaveTextContent('Sources disagree: MIT (github) · Apache-2.0 (npm)');
    expect(conflict).not.toHaveTextContent('Not stated');
  });

  it('Scenario: the Save button is named by the word it shows, before and after saving (WCAG 2.5.3)', async () => {
    window.localStorage.clear();
    mockApi({ [`GET /resources/${PG.id}`]: () => makeDetail({ card: PG }) });
    renderApp(`/resource/${PG.id}`);
    const save = await screen.findByRole('button', { name: 'Save Postgres MCP' });
    expect(save).toHaveAttribute('aria-pressed', 'false');
    await userEvent.click(save);
    const saved = screen.getByRole('button', { name: 'Saved Postgres MCP' });
    expect(saved).toHaveTextContent(/^Saved$/);
    expect(saved).toHaveAttribute('aria-pressed', 'true');
    window.localStorage.clear();
  });

  it('has no axe violations', async () => {
    mockApi({ [`GET /resources/${PG.id}`]: () => makeDetail({ card: PG }) });
    const { container } = renderApp(`/resource/${PG.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Postgres MCP' });
    await settle();
    expect(await axeViolations(container)).toEqual([]);
  });
});
