import { screen, within } from '@testing-library/react';
import { axeViolations } from '@/test/a11y';
import { makeCard } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp } from '@/test/render';
import { HUBS, hubOf } from '@/lib/hubs';

const ID = '8a0e6c1e-2f43-5b6e-9d0a-1f1e2d3c4b5a';

describe('Hub page', () => {
  it('lists the newest resources of its type, each linking to its page', async () => {
    const base = makeCard();
    const m = mockApi({
      'GET /hubs/MCP_SERVER': () => ({
        type: 'MCP_SERVER',
        items: [
          makeCard({
            id: ID,
            title: 'postgres-mcp',
            type: 'MCP_SERVER',
            license: 'MIT',
            sources: [{ ...base.sources[0]!, url: 'https://github.com/acme/postgres-mcp' }],
          }),
        ],
        generatedAt: '2026-11-19T10:00:00Z',
      }),
    });
    const { container } = renderApp('/mcp-servers');
    expect(
      await screen.findByRole('heading', { level: 1, name: 'MCP servers' }),
    ).toBeInTheDocument();
    const link = await screen.findByRole('link', { name: 'postgres-mcp' });
    expect(link).toHaveAttribute('href', `/resource/${ID}/postgres-mcp`);
    expect(screen.getByText('github.com · MIT')).toBeInTheDocument();
    expect(m.callsTo('GET', '/hubs/MCP_SERVER')).toHaveLength(1);
    expect(await axeViolations(container)).toEqual([]);
  });

  it('says so when the list is empty', async () => {
    mockApi({
      'GET /hubs/COURSE': () => ({
        type: 'COURSE',
        items: [],
        generatedAt: '2026-11-19T10:00:00Z',
      }),
    });
    renderApp('/courses');
    expect(await screen.findByText('Nothing here yet.')).toBeInTheDocument();
  });

  it('says so when the list cannot be read', async () => {
    mockApi({ 'GET /hubs/PAPER': () => problem(503, 'Unavailable') });
    renderApp('/papers');
    expect(await screen.findByText('Could not load this list.')).toBeInTheDocument();
  });

  it('is reachable from the footer of every page', async () => {
    mockApi();
    renderApp('/mission');
    const browse = await screen.findByRole('navigation', { name: 'Browse by type' });
    for (const hub of HUBS) {
      expect(
        within(browse)
          .getAllByRole('link')
          .some((a) => a.getAttribute('href') === `/${hub.path}`),
      ).toBe(true);
    }
  });
});

describe('hubs', () => {
  it('maps every hub path to one type, and nothing else', () => {
    expect(new Set(HUBS.map((h) => h.type)).size).toBe(HUBS.length);
    expect(hubOf('/mcp-servers/')?.type).toBe('MCP_SERVER');
    expect(hubOf('/events')).toBeUndefined();
  });
});
