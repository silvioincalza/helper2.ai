import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { GuideResponse } from '@/api/types';
import { heldSse, mockApi, sse } from '@/test/mockApi';
import { renderApp } from '@/test/render';

const OK: GuideResponse = {
  status: 'OK',
  searchQuery: 'new tools',
  recommendations: [],
  candidatesConsidered: 0,
  sources: [],
  notices: [],
  generatedAt: '2026-10-30T10:00:00Z',
};
const ASK_BACK: GuideResponse = {
  ...OK,
  status: 'NEEDS_CLARIFICATION',
  clarification: { question: 'New tools for what?', options: ['coding', 'agents'] },
};

const PICKED: GuideResponse = {
  ...OK,
  candidatesConsidered: 1,
  recommendations: [
    {
      resourceId: 'bbbbbbbb-bbbb-5bbb-8bbb-bbbbbbbbbbbb',
      title: 'Postgres MCP',
      url: 'https://github.com/acme/postgres-mcp',
      type: 'MCP_SERVER',
      reason: 'Read-only access fits an agent that only reads.',
      quote: 'read-only access',
      sourceIds: ['github'],
      aiReasoned: true,
      reasonCheck: 'NOT_CHECKED',
    },
  ],
};
const picksBar = () => screen.getByRole('region', { name: 'Guide picks' });

async function askedAgain(first: GuideResponse) {
  const next = heldSse();
  let asked = 0;
  const m = mockApi({
    'POST /guide/stream': () => (asked++ === 0 ? sse(first) : next.response()),
  });
  renderApp('/search?q=new+tools&ask=new+tools');
  await waitFor(() => expect(picksBar()).not.toHaveClass('is-reading'));
  await userEvent.click(bestFor().getByRole('button', { name: 'Build with AI' }));
  await userEvent.click(await screen.findByRole('button', { name: 'Ask again' }));
  await waitFor(() => expect(guideCalls(m)).toHaveLength(2));
  return next;
}

const guideCalls = (m: ReturnType<typeof mockApi>) =>
  m.callsTo('POST', '/guide/stream').map((c) => c.body);
const bestFor = () => within(screen.getByRole('group', { name: 'Best for:' }));
const hint = () => screen.queryByText('Filters changed since you asked.');

async function answered(reply: GuideResponse = OK) {
  const m = mockApi({ 'POST /guide/stream': () => sse(reply) });
  const app = renderApp('/search?q=new+tools&ask=new+tools');
  await waitFor(() => expect(guideCalls(m)).toHaveLength(1));
  await screen.findByRole('group', { name: 'Best for:' });
  await waitFor(() =>
    expect(screen.getByRole('region', { name: 'Guide picks' })).not.toHaveClass('is-reading'),
  );
  return { m, router: app.router };
}

describe('Feature: filters changed after the guide answered', () => {
  it('Scenario: a changed Best for shows the hint, and it spends nothing until clicked', async () => {
    const { m } = await answered();
    expect(hint()).toBeNull();

    await userEvent.click(bestFor().getByRole('button', { name: 'Build with AI' }));

    expect(await screen.findByText('Filters changed since you asked.')).toBeInTheDocument();
    expect(hint()!.closest('[role="status"]')).not.toBeNull();
    expect(guideCalls(m)).toHaveLength(1);
  });

  it('Scenario: Ask again sends the question with the filters of now, and the hint goes away', async () => {
    const { m } = await answered();
    await userEvent.click(bestFor().getByRole('button', { name: 'Build with AI' }));

    await userEvent.click(await screen.findByRole('button', { name: 'Ask again' }));

    await waitFor(() => expect(guideCalls(m)).toHaveLength(2));
    expect(guideCalls(m)[1]).toEqual({ request: 'new tools', path: 'BUILD_WITH_AI' });
    await waitFor(() => expect(hint()).toBeNull());
  });

  it('Scenario: changing the filter back hides the hint', async () => {
    await answered();
    await userEvent.click(bestFor().getByRole('button', { name: 'Build with AI' }));
    await screen.findByText('Filters changed since you asked.');

    await userEvent.click(bestFor().getByRole('button', { name: 'Anyone' }));

    await waitFor(() => expect(hint()).toBeNull());
  });

  it('Scenario: the hint shows next to a question back too', async () => {
    await answered(ASK_BACK);
    await userEvent.click(bestFor().getByRole('button', { name: 'Build with AI' }));

    expect(await screen.findByText('Filters changed since you asked.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'agents' })).toBeInTheDocument();
  });

  it('Scenario: after Ask again and a filter change, Back restores the answer without a new request', async () => {
    const { m, router } = await answered();
    await userEvent.click(bestFor().getByRole('button', { name: 'Build with AI' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Ask again' }));
    await waitFor(() => expect(guideCalls(m)).toHaveLength(2));
    await userEvent.click(bestFor().getByRole('button', { name: 'Apps I can use' }));
    await screen.findByText('Filters changed since you asked.');

    await act(() => router.navigate('/mission'));
    await act(() => router.navigate(-1));

    expect(await screen.findByText('Filters changed since you asked.')).toBeInTheDocument();
    expect(guideCalls(m)).toHaveLength(2);
  });

  it('Scenario: Ask again keeps the old picks, marked busy, until the new answer sends its first event', async () => {
    const next = await askedAgain(PICKED);

    expect(picksBar()).toHaveClass('is-picks', 'is-stale');
    expect(picksBar()).toHaveAttribute('aria-busy', 'true');
    expect(within(picksBar()).getByText('Postgres MCP')).toBeInTheDocument();
    expect(hint()).toBeNull();

    act(() => next.step('retrieved', { candidates: 3, sources: [] }));

    await waitFor(() => expect(picksBar()).toHaveClass('is-reading'));
    expect(picksBar()).not.toHaveClass('is-stale');
    expect(picksBar()).toHaveAttribute('aria-busy', 'false');
    expect(within(picksBar()).queryByText('Postgres MCP')).toBeNull();
  });

  it('Scenario: the new answer replaces the kept picks when it comes with no event before it', async () => {
    const next = await askedAgain(PICKED);
    expect(picksBar()).toHaveClass('is-stale');

    act(() => next.finish(OK));

    await waitFor(() => expect(picksBar()).toHaveClass('is-none'));
    expect(picksBar()).not.toHaveClass('is-stale');
    expect(within(picksBar()).queryByText('Postgres MCP')).toBeNull();
  });

  it('Scenario: Ask again with no picks to keep shows the reading line at once', async () => {
    await askedAgain(OK);

    expect(picksBar()).toHaveClass('is-reading');
    expect(picksBar()).not.toHaveClass('is-stale');
  });
});
