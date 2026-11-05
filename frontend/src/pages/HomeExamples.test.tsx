import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { AudiencePath } from '@/api/types';
import { axeViolations } from '@/test/a11y';
import { makeSearch } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

const served = (n: number, path: AudiencePath, aiDrafted = false) => ({
  id: `66666666-6666-5666-8666-${String(n).padStart(12, '0')}`,
  label: `Served ${n}`,
  query: `served query ${n}`,
  path,
  aiDrafted,
});

const POOL = {
  examples: [
    served(1, 'USE_AI'),
    served(2, 'USE_AI'),
    served(3, 'BUILD_WITH_AI'),
    served(4, 'BUILD_WITH_AI'),
  ],
};

const BUILT_IN = [
  'Summarise research papers',
  'Take meeting notes',
  'Postgres MCP server',
  'Vector database',
];

const examples = async () =>
  (await screen.findByRole('heading', { name: 'Try:' })).closest('section') as HTMLElement;
const chips = async () =>
  (await within(await examples()).findAllByRole('button')).map((b) => b.textContent);

describe('home examples: from the served pool, the built-in list as fallback', () => {
  beforeEach(() => vi.spyOn(Math, 'random').mockReturnValue(0));
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('shows the served pool when it answers, two per path, people who use AI first', async () => {
    const m = mockApi({ 'GET /home/examples': () => POOL });
    renderApp('/');
    expect(await chips()).toEqual(['Served 1', 'Served 2', 'Served 3', 'Served 4']);
    expect(new URLSearchParams(m.callsTo('GET', '/home/examples')[0]?.search).get('lang')).toBe(
      'en',
    );
  });

  it('a served example searches its own query and path, and does not ask the guide', async () => {
    const m = mockApi({ 'GET /home/examples': () => POOL });
    const { router, params } = renderApp('/');
    await userEvent.click(await screen.findByRole('button', { name: 'Served 3' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/search'));
    expect(params().get('q')).toBe('served query 3');
    expect(params().get('path')).toBe('BUILD_WITH_AI');
    expect(params().get('ask')).toBeNull();
    await settle();
    expect(m.callsTo('POST', '/guide/stream')).toHaveLength(0);
  });

  it('the results page shows the example\'s path as chosen under "Best for"', async () => {
    mockApi({ 'GET /home/examples': () => POOL, 'GET /search': () => makeSearch() });
    renderApp('/');
    await userEvent.click(await screen.findByRole('button', { name: 'Served 1' }));
    const bestFor = await screen.findByRole('group', { name: 'Best for:' });
    expect(within(bestFor).getByRole('button', { name: 'Apps I can use' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(within(bestFor).getByRole('button', { name: 'All' })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
  });

  it('uses the built-in list when the pool fails', async () => {
    mockApi({ 'GET /home/examples': () => problem(503, 'Service Unavailable') });
    renderApp('/');
    expect(await chips()).toEqual(BUILT_IN);
  });

  it('uses the built-in list when a path has fewer than two examples', async () => {
    mockApi({ 'GET /home/examples': () => ({ examples: POOL.examples.slice(0, 3) }) });
    renderApp('/');
    expect(await chips()).toEqual(BUILT_IN);
  });

  it('waits 800 ms for a slow pool, holding the row, then shows the built-in list', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
    mockApi({ 'GET /home/examples': () => new Promise<never>(() => undefined) });
    renderApp('/');
    const section = await examples();
    await act(() => vi.advanceTimersByTimeAsync(700));
    // Waiting: no chip to click yet, but the built-in pick holds the row's height, unseen.
    expect(within(section).queryAllByRole('button')).toEqual([]);
    expect(section).toHaveAttribute('aria-busy', 'true');
    const waiting = section.querySelector('.home-examples__waiting');
    expect(waiting).toHaveAttribute('aria-hidden', 'true');
    expect(waiting).toHaveTextContent('Summarise research papers');
    await act(() => vi.advanceTimersByTimeAsync(100));
    expect(
      within(section)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(BUILT_IN);
    expect(section).toHaveAttribute('aria-busy', 'false');
    expect(section.querySelector('.home-examples__waiting')).toBeNull();
  });

  it('labels and marks an example a model drafted; the others carry no mark', async () => {
    mockApi({
      'GET /home/examples': () => ({
        examples: [served(1, 'USE_AI', true), ...POOL.examples.slice(1)],
        ai: {
          aiGenerated: true,
          provider: 'nebius',
          models: ['nvidia/nemotron-nano'],
          fields: ['examples[].label', 'examples[].query'],
          policy: '/mission',
        },
      }),
    });
    const { container } = renderApp('/');
    const drafted = await screen.findByRole('button', {
      name: 'Served 1 Drafted with AI, checked by a person',
    });
    const label = within(drafted).getByText('Served 1');
    expect(label).toHaveAttribute('data-ai-generated', 'true');
    expect(label).toHaveAttribute('data-ai-model', 'nvidia/nemotron-nano');
    expect(within(drafted).getByTitle('Drafted with AI, checked by a person')).toHaveTextContent(
      'AI',
    );
    const others = within(await examples())
      .getAllByRole('button')
      .filter((b) => b !== drafted);
    expect(others).toHaveLength(3);
    for (const chip of others) {
      expect(chip.querySelector('[data-ai-generated]')).toBeNull();
      expect(chip).not.toHaveTextContent('AI');
    }
    await settle();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('built-in examples carry no AI mark', async () => {
    mockApi();
    renderApp('/');
    expect(await chips()).toEqual(BUILT_IN);
    expect((await examples()).querySelector('[data-ai-generated]')).toBeNull();
  });
});
