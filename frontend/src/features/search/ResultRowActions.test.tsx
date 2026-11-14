import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { BOOKMARK_KEY } from '@/lib/bookmarks';
import { axeViolations } from '@/test/a11y';
import { makeCard, makeSearch } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

const PG = makeCard({
  id: 'bbbbbbbb-bbbb-5bbb-8bbb-bbbbbbbbbbbb',
  title: 'Postgres MCP',
  type: 'MCP_SERVER',
  url: 'https://github.com/acme/postgres-mcp',
});
const ODD = makeCard({
  id: 'cccccccc-cccc-5ccc-8ccc-cccccccccccc',
  title: 'Odd Link',
  url: 'ftp://files.example/odd',
});

const rowOf = (title: string) => screen.getByRole('link', { name: title }).closest('li')!;

async function results() {
  mockApi({ 'GET /search': () => makeSearch({ results: [PG, ODD], total: 2 }) });
  renderApp('/search?q=postgres');
  await screen.findByText('Postgres MCP');
}

describe('Feature: a result row gets the visitor to the tool, and keeps it', () => {
  afterEach(() => window.localStorage.clear());

  it('Scenario: the host in a row opens the resource itself in a new tab; the title still opens our page', async () => {
    await results();
    const row = within(rowOf('Postgres MCP'));
    const host = row.getByRole('link', { name: 'github.com (opens in a new tab)' });
    expect(host).toHaveAttribute('href', 'https://github.com/acme/postgres-mcp');
    expect(host).toHaveAttribute('target', '_blank');
    expect(host).toHaveAttribute('rel', 'noopener noreferrer nofollow ugc');
    expect(row.getByRole('link', { name: 'Postgres MCP' })).toHaveAttribute(
      'href',
      expect.stringMatching(/^\/resource\/bbbbbbbb-/),
    );
  });

  it('Scenario: each result title is a heading, so a screen reader can jump from result to result', async () => {
    await results();
    expect(screen.getByRole('heading', { level: 3, name: 'Postgres MCP' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 3, name: 'Odd Link' })).toBeInTheDocument();
  });

  it('Scenario: a host that is not a web address stays plain text', async () => {
    await results();
    const row = within(rowOf('Odd Link'));
    expect(row.getAllByRole('link')).toHaveLength(1);
  });

  it('Scenario: Save on a row keeps the result without leaving the list', async () => {
    await results();
    const save = within(rowOf('Postgres MCP')).getByRole('button', { name: 'Save Postgres MCP' });
    expect(save).toHaveAttribute('aria-pressed', 'false');
    await userEvent.click(save);
    expect(save).toHaveAttribute('aria-pressed', 'true');
    expect(save).toHaveTextContent('Saved');
    expect(screen.getByRole('link', { name: 'Odd Link' })).toBeInTheDocument();
    const stored = JSON.parse(window.localStorage.getItem(BOOKMARK_KEY) ?? '[]') as {
      resourceId: string;
    }[];
    expect(stored.map((b) => b.resourceId)).toEqual([PG.id]);
  });

  it('Scenario: the row with its two actions has no axe violations', async () => {
    await results();
    await settle();
    expect(await axeViolations(document.body)).toEqual([]);
  });
});
