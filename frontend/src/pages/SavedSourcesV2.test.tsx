import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Bookmark, SourceInfo } from '@/api/types';
import { BOOKMARK_KEY } from '@/lib/bookmarks';
import { axeViolations } from '@/test/a11y';
import { SOURCES, USER } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

vi.mock('@/lib/features', () => ({ SAVED_ENABLED: true }));

const saved: Bookmark[] = [
  { resourceId: '11111111-1111-5111-8111-111111111111', title: 'Postgres MCP', url: 'https://github.com/a/pg', type: 'MCP_SERVER', savedAt: '2026-10-20T10:00:00Z' },
  { resourceId: '22222222-2222-5222-8222-222222222222', title: 'pgvector', url: 'https://github.com/pgvector/pgvector', type: 'LIBRARY', savedAt: '2026-10-21T10:00:00Z' },
];

describe('Feature: saved resources in design v2', () => {
  beforeEach(() => window.localStorage.setItem(BOOKMARK_KEY, JSON.stringify(saved)));
  afterEach(() => window.localStorage.clear());

  it('Scenario: the saved list shows each item with its type and host, and tabs filter by type', async () => {
    mockApi();
    renderApp('/saved');
    expect(await screen.findByRole('heading', { level: 1, name: 'Saved resources' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Postgres MCP' })).toBeInTheDocument();
    expect(screen.getAllByText('github.com', { selector: '.h2-saved__host' })).toHaveLength(2);
    const tabs = screen.getByRole('navigation', { name: 'Saved by type' });
    await userEvent.click(within(tabs).getByRole('button', { name: /Library/ }));
    expect(screen.queryByRole('link', { name: 'Postgres MCP' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'pgvector' })).toBeInTheDocument();
  });

  it('Scenario: removing the last item of the picked type shows all the others again', async () => {
    mockApi();
    renderApp('/saved');
    const tabs = await screen.findByRole('navigation', { name: 'Saved by type' });
    await userEvent.click(within(tabs).getByRole('button', { name: /Library/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Remove pgvector from saved' }));
    expect(screen.getByRole('link', { name: 'Postgres MCP' })).toBeInTheDocument();
  });

  it('Scenario: Remove takes the item out of the list and out of browser storage', async () => {
    mockApi();
    renderApp('/saved');
    await userEvent.click(await screen.findByRole('button', { name: 'Remove Postgres MCP from saved' }));
    expect(screen.queryByRole('link', { name: 'Postgres MCP' })).not.toBeInTheDocument();
    expect(JSON.parse(window.localStorage.getItem(BOOKMARK_KEY) ?? '[]')).toHaveLength(1);
  });

  it('Scenario: with nothing saved the page says so and offers a way back to search', async () => {
    window.localStorage.clear();
    mockApi();
    renderApp('/saved');
    expect(await screen.findByText('Nothing saved yet.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to search' })).toHaveAttribute('href', '/');
    await settle();
    expect(await axeViolations(document.body)).toEqual([]);
  });
});

const sources: SourceInfo[] = [
  ...SOURCES,
  { id: 'arxiv', name: 'arXiv', mode: 'INDEXED', enabled: true, homepage: 'https://arxiv.org', termsUrl: null, attribution: 'Thanks to arXiv', resourceTypes: ['PAPER'], lastOutcome: 'RATE_LIMITED', lastCheckedAt: '2026-10-22T08:00:00Z' },
  { id: 'npm', name: 'npm', mode: 'LIVE', enabled: true, homepage: 'https://www.npmjs.com', termsUrl: null, attribution: null, resourceTypes: ['LIBRARY'], lastOutcome: 'FAILED', lastCheckedAt: '2026-10-22T08:30:00Z' },
];

describe('Feature: the sources page in design v2', () => {
  it('Scenario: every source says its last check in words, and the summary counts them', async () => {
    mockApi({ 'GET /sources': () => sources });
    renderApp('/sources');
    expect(await screen.findByText('1 of 4 sources answered at their last check')).toBeInTheDocument();
    expect(screen.getByText('1 rate limited or skipped')).toBeInTheDocument();
    expect(screen.getByText('1 failed')).toBeInTheDocument();
    expect(screen.getByText('1 not checked yet')).toBeInTheDocument();
    const npm = screen.getByRole('heading', { level: 3, name: /npm/ }).closest('li') as HTMLElement;
    expect(within(npm).getByText('Failed')).toBeInTheDocument();
    const arxiv = screen.getByRole('heading', { level: 3, name: /arXiv/ }).closest('li') as HTMLElement;
    expect(within(arxiv).getByText('Rate limited')).toBeInTheDocument();
    expect(within(arxiv).getByText('Thanks to arXiv')).toBeInTheDocument();
  });

  it('Scenario: each source links its terms as an external, nofollow link', async () => {
    mockApi();
    renderApp('/sources');
    const gh = (await screen.findByRole('heading', { level: 3, name: /GitHub/ })).closest('li') as HTMLElement;
    expect(within(gh).getByRole('link', { name: /Terms/ })).toHaveAttribute('rel', 'noopener noreferrer nofollow');
  });

  it('Scenario: the mode chips show only the sources of that mode', async () => {
    mockApi({ 'GET /sources': () => sources });
    renderApp('/sources');
    const chips = await screen.findByRole('group', { name: 'Show sources by mode' });
    expect(within(chips).queryByRole('button', { name: 'Demo' })).not.toBeInTheDocument();
    await userEvent.click(within(chips).getByRole('button', { name: 'Indexed' }));
    expect(screen.getByRole('heading', { level: 3, name: /arXiv/ })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 3, name: /GitHub/ })).not.toBeInTheDocument();
    expect(within(chips).getByRole('button', { name: 'Indexed' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('Scenario: the page has one h1 and no axe violations', async () => {
    mockApi({ 'GET /sources': () => sources });
    renderApp('/sources');
    await screen.findByText('1 of 4 sources answered at their last check');
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(await axeViolations(document.body)).toEqual([]);
  });
});

describe('Feature: your account in design v2', () => {
  it('Scenario: the delete flow is unchanged: confirm dialog, cancel deletes nothing', async () => {
    const m = mockApi({ 'GET /me': () => USER });
    renderApp('/account');
    await userEvent.click(await screen.findByRole('button', { name: 'Delete my account' }));
    const dialog = await screen.findByRole('alertdialog', { name: 'Delete your account?' });
    await waitFor(() => expect(within(dialog).getByRole('button', { name: 'Cancel' })).toHaveFocus());
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(m.callsTo('DELETE', '/me')).toHaveLength(0);
    expect(await axeViolations(document.body)).toEqual([]);
  });
});
