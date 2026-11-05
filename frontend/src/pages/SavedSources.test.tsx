import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Bookmark, SourceInfo } from '@/api/types';
import { BOOKMARK_KEY } from '@/lib/bookmarks';
import { axeViolations } from '@/test/a11y';
import { HEALTH_SOURCES, SOURCES, USER } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

const saved: Bookmark[] = [
  {
    resourceId: '11111111-1111-5111-8111-111111111111',
    title: 'Postgres MCP',
    url: 'https://github.com/a/pg',
    type: 'MCP_SERVER',
    savedAt: '2026-10-20T10:00:00Z',
  },
  {
    resourceId: '22222222-2222-5222-8222-222222222222',
    title: 'pgvector',
    url: 'https://github.com/pgvector/pgvector',
    type: 'LIBRARY',
    savedAt: '2026-10-21T10:00:00Z',
  },
];

describe('Feature: saved resources', () => {
  beforeEach(() => window.localStorage.setItem(BOOKMARK_KEY, JSON.stringify(saved)));
  afterEach(() => window.localStorage.clear());

  it('Scenario: the saved list shows each item with its type and host, and tabs filter by type', async () => {
    mockApi();
    renderApp('/saved');
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Saved resources' }),
    ).toBeInTheDocument();
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
    await userEvent.click(
      await screen.findByRole('button', { name: 'Remove Postgres MCP from saved' }),
    );
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
  {
    id: 'arxiv',
    name: 'arXiv',
    shortName: 'arXiv',
    external: true,
    mode: 'INDEXED',
    enabled: true,
    metered: false,
    homepage: 'https://arxiv.org',
    termsUrl: null,
    attribution: 'Thanks to arXiv',
    resourceTypes: ['PAPER'],
    lastOutcome: 'RATE_LIMITED',
    lastCheckedAt: '2026-10-22T08:00:00Z',
  },
  {
    id: 'npm',
    name: 'npm',
    shortName: 'npm',
    external: true,
    mode: 'LIVE',
    enabled: true,
    metered: false,
    homepage: 'https://www.npmjs.com',
    termsUrl: null,
    attribution: null,
    resourceTypes: ['LIBRARY'],
    lastOutcome: 'FAILED',
    lastCheckedAt: '2026-10-22T08:30:00Z',
  },
];

describe('Feature: the sources page', () => {
  it('Scenario: every source says its last check in words, and the summary counts them', async () => {
    mockApi({ 'GET /sources': () => sources });
    renderApp('/sources');
    expect(
      await screen.findByText('1 of 4 sources answered at their last check'),
    ).toBeInTheDocument();
    expect(screen.getByText('1 rate limited or skipped')).toBeInTheDocument();
    expect(screen.getByText('1 failed')).toBeInTheDocument();
    expect(screen.getByText('1 not checked yet')).toBeInTheDocument();
    const npm = screen.getByRole('heading', { level: 3, name: /npm/ }).closest('li') as HTMLElement;
    expect(within(npm).getByText('Failed')).toBeInTheDocument();
    const arxiv = screen
      .getByRole('heading', { level: 3, name: /arXiv/ })
      .closest('li') as HTMLElement;
    expect(within(arxiv).getByText('Rate limited')).toBeInTheDocument();
    expect(within(arxiv).getByText('Thanks to arXiv')).toBeInTheDocument();
  });

  it('Scenario: each source links its terms as an external, nofollow link', async () => {
    mockApi();
    renderApp('/sources');
    const gh = (await screen.findByRole('heading', { level: 3, name: /GitHub/ })).closest(
      'li',
    ) as HTMLElement;
    expect(within(gh).getByRole('link', { name: /Terms/ })).toHaveAttribute(
      'rel',
      'noopener noreferrer nofollow',
    );
  });

  it('Scenario: the mode chips show only the sources of that mode', async () => {
    mockApi({ 'GET /sources': () => sources });
    renderApp('/sources');
    const chips = await screen.findByRole('group', { name: 'Show sources by mode' });
    expect(within(chips).queryByRole('button', { name: 'Demo' })).not.toBeInTheDocument();
    await userEvent.click(within(chips).getByRole('button', { name: 'Indexed' }));
    expect(screen.getByRole('heading', { level: 3, name: /arXiv/ })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 3, name: /GitHub/ })).not.toBeInTheDocument();
    expect(within(chips).getByRole('button', { name: 'Indexed' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  it('Scenario: each card shows its response time after the last check, or "No calls yet" on a live source', async () => {
    mockApi({ 'GET /sources': () => HEALTH_SOURCES });
    renderApp('/sources');
    const gh = (await screen.findByRole('heading', { level: 3, name: /GitHub/ })).closest(
      'li',
    ) as HTMLElement;
    const terms = [...gh.querySelectorAll('dt')].map((d) => d.textContent);
    expect(terms.indexOf('Response time')).toBe(terms.indexOf('Last check') + 1);
    const time = within(gh).getByText('Response time').nextElementSibling as HTMLElement;
    expect(time).toHaveTextContent('240 ms');
    expect(within(time).getByText('240 milliseconds')).toHaveClass('sr-only');
    const hf = screen
      .getByRole('heading', { level: 3, name: /Hugging Face/ })
      .closest('li') as HTMLElement;
    expect(within(hf).getByText('Response time').nextElementSibling).toHaveTextContent(
      'No calls yet',
    );
    // A curated source is not called: no response time row.
    const seed = screen
      .getByRole('heading', { level: 3, name: /Helper2 seed/ })
      .closest('li') as HTMLElement;
    expect(within(seed).queryByText('Response time')).not.toBeInTheDocument();
    expect(
      screen.getByText('Response time and pauses are from the server that answered this page.'),
    ).toBeInTheDocument();
  });

  it('Scenario: a paused source says since when and that it retries; the summary still counts the last outcome only', async () => {
    mockApi({ 'GET /sources': () => HEALTH_SOURCES });
    renderApp('/sources');
    const npm = (await screen.findByRole('heading', { level: 3, name: /npm/ })).closest(
      'li',
    ) as HTMLElement;
    expect(
      within(npm).getByText(
        'Paused after repeated failures, since 09:14:05 UTC. It tries again on its own.',
      ),
    ).toBeInTheDocument();
    const hf = screen
      .getByRole('heading', { level: 3, name: /Hugging Face/ })
      .closest('li') as HTMLElement;
    expect(within(hf).getByText('Trying again')).toBeInTheDocument();
    const gh = screen
      .getByRole('heading', { level: 3, name: /GitHub/ })
      .closest('li') as HTMLElement;
    expect(within(gh).queryByText(/Paused|Trying again/)).not.toBeInTheDocument();
    // npm is paused but its last call succeeded: it counts as answered, not as limited.
    expect(screen.getByText('3 of 5 sources answered at their last check')).toBeInTheDocument();
    expect(screen.queryByText(/rate limited or skipped/)).not.toBeInTheDocument();
  });

  it("Scenario: only the metered source shows today's web credits, with or without a daily cap", async () => {
    mockApi({ 'GET /sources': () => HEALTH_SOURCES });
    const first = renderApp('/sources');
    const tavily = (await screen.findByRole('heading', { level: 3, name: /Tavily/ })).closest(
      'li',
    ) as HTMLElement;
    expect(within(tavily).getByText('Web credits today').nextElementSibling).toHaveTextContent(
      '312 of 1,000 (all visitors)',
    );
    expect(screen.getAllByText('Web credits today')).toHaveLength(1);
    first.unmount();
    mockApi({
      'GET /sources': () =>
        HEALTH_SOURCES.map((s) =>
          s.id === 'tavily' ? { ...s, webCredits: { usedToday: 312, dailyCap: null } } : s,
        ),
    });
    renderApp('/sources');
    const again = (await screen.findByRole('heading', { level: 3, name: /Tavily/ })).closest(
      'li',
    ) as HTMLElement;
    expect(within(again).getByText('Web credits today').nextElementSibling).toHaveTextContent(
      '312 (no daily cap)',
    );
  });

  it('Scenario: when the credits cannot be read, the metered source says they are not available', async () => {
    mockApi({
      'GET /sources': () =>
        HEALTH_SOURCES.map((s) => (s.id === 'tavily' ? { ...s, webCredits: null } : s)),
    });
    renderApp('/sources');
    const tavily = (await screen.findByRole('heading', { level: 3, name: /Tavily/ })).closest(
      'li',
    ) as HTMLElement;
    expect(within(tavily).getByText('Web credits today').nextElementSibling).toHaveTextContent(
      'Not available right now',
    );
  });

  it('Scenario: the page has one h1 and no axe violations', async () => {
    mockApi({ 'GET /sources': () => sources });
    renderApp('/sources');
    await screen.findByText('1 of 4 sources answered at their last check');
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(await axeViolations(document.body)).toEqual([]);
  });
});

describe('Feature: your account', () => {
  it('Scenario: the delete flow is unchanged: confirm dialog, cancel deletes nothing', async () => {
    const m = mockApi({ 'GET /me': () => USER });
    renderApp('/account');
    await userEvent.click(await screen.findByRole('button', { name: 'Delete my account' }));
    const dialog = await screen.findByRole('alertdialog', { name: 'Delete your account?' });
    await waitFor(() =>
      expect(within(dialog).getByRole('button', { name: 'Cancel' })).toHaveFocus(),
    );
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(m.callsTo('DELETE', '/me')).toHaveLength(0);
    expect(await axeViolations(document.body)).toEqual([]);
  });
});

describe('Feature: the source list cannot load', () => {
  it('Scenario: Try again keeps focus on the list heading and shows the sources', async () => {
    let fail = true;
    mockApi({ 'GET /sources': () => (fail ? problem(503, 'Service Unavailable') : sources) });
    renderApp('/sources');
    const alert = await screen.findByRole('alert');
    expect(
      within(alert).getByRole('heading', { name: 'Could not load the source list.' }),
    ).toBeInTheDocument();
    fail = false;
    within(alert)
      .getByRole('button', { name: /Try again/ })
      .focus();
    await userEvent.keyboard('{Enter}');
    expect(
      await screen.findByText('1 of 4 sources answered at their last check'),
    ).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 2, name: 'Every source' })).toHaveFocus(),
    );
  });

  it('the Sources page asks again when the copy Home loaded is older than the cache limit', async () => {
    const now = vi.spyOn(Date, 'now').mockReturnValue(1_000);
    const m = mockApi();
    const { router } = renderApp('/');
    await waitFor(() => expect(m.callsTo('GET', '/sources')).toHaveLength(1));
    await settle();
    // Past the recent-answers cache's 5 minutes.
    now.mockReturnValue(1_000 + 5 * 60_000 + 1);
    await act(() => router.navigate('/sources'));
    await waitFor(() => expect(m.callsTo('GET', '/sources')).toHaveLength(2));
    await settle();
  });
});

/** The reader leaves the tab and comes back [ms] later. */
async function comeBackAfter(ms: number) {
  const now = Date.now();
  vi.spyOn(Date, 'now').mockReturnValue(now + ms);
  const set = (state: DocumentVisibilityState) => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state });
    document.dispatchEvent(new Event('visibilitychange'));
  };
  await act(async () => {
    set('hidden');
    set('visible');
  });
}

describe('Feature: the sources page refreshes when the reader comes back', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => 'visible',
    });
  });

  it('Scenario: back after a while, the page asks again and shows the new outcome without a loading line', async () => {
    let answer: SourceInfo[] = SOURCES;
    let release!: () => void;
    let held = false;
    const m = mockApi({
      'GET /sources': () =>
        held
          ? new Promise((r) => {
              release = () => r(answer);
            })
          : answer,
    });
    renderApp('/sources');
    const gh = (await screen.findByRole('heading', { level: 3, name: /GitHub/ })).closest(
      'li',
    ) as HTMLElement;
    expect(within(gh).getByText('Succeeded')).toBeInTheDocument();
    const chip = screen.queryByRole('button', { name: 'Live' });
    const before = m.callsTo('GET', '/sources').length;
    held = true;
    answer = SOURCES.map((s) => (s.id === 'github' ? { ...s, lastOutcome: 'FAILED' as const } : s));
    await comeBackAfter(2 * 60_000);
    await waitFor(() => expect(m.callsTo('GET', '/sources')).toHaveLength(before + 1));
    // While it loads, the old list stays: no loading line, no blink.
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 3, name: /GitHub/ })).toBeInTheDocument();
    if (chip) expect(screen.getByRole('button', { name: 'Live' })).toBe(chip);
    await act(async () => release());
    const after = screen
      .getByRole('heading', { level: 3, name: /GitHub/ })
      .closest('li') as HTMLElement;
    expect(await within(after).findByText('Failed')).toBeInTheDocument();
  });

  it('Scenario: back within a minute, the page does not ask again', async () => {
    const m = mockApi();
    renderApp('/sources');
    await screen.findByRole('heading', { level: 3, name: /GitHub/ });
    await settle();
    const before = m.callsTo('GET', '/sources').length;
    await comeBackAfter(30_000);
    await settle();
    expect(m.callsTo('GET', '/sources')).toHaveLength(before);
  });

  it('Scenario: a refresh that fails keeps the old list and says so quietly', async () => {
    let fail = false;
    mockApi({ 'GET /sources': () => (fail ? problem(503, 'Unavailable') : SOURCES) });
    renderApp('/sources');
    await screen.findByRole('heading', { level: 3, name: /GitHub/ });
    fail = true;
    await comeBackAfter(2 * 60_000);
    expect(
      await screen.findByText(/^Could not refresh; showing the list from \d\d:\d\d UTC$/),
    ).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 3, name: /GitHub/ })).toBeInTheDocument();
    expect(screen.queryByText('Could not load the source list.')).not.toBeInTheDocument();
  });
});
