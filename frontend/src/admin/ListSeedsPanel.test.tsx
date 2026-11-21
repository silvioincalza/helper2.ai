import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ListSeedEntry, ListSeeds } from '@/api/types';
import { axeViolations } from '@/test/a11y';
import { ADMIN } from '@/test/fixtures';
import { json, mockApi } from '@/test/mockApi';
import { renderAdmin, settle } from '@/test/render';

const PAGE = 'https://blog.acme.dev/best-ai-tools';

const entry = (address: string, reason: string | null = null): ListSeedEntry => ({
  id: address.startsWith('https://')
    ? '6f1c3a52-8d0e-3b7a-9c41-2e5d7f8a9b10'
    : '0b6e2d4f-1a3c-3e5f-8a7b-9c0d1e2f3a4b',
  kind: address.startsWith('https://') ? 'WEB_PAGE' : 'GITHUB_REPOSITORY',
  origin: 'ADMINISTRATOR',
  address,
  addedBy: '42',
  addedAt: '2026-11-19T21:00:00Z',
  reason,
});

const SEED: ListSeeds = { max: 50, lists: [entry('acme/awesome-rag', 'the best RAG list')] };

const refused = (status: number, code: string, detail: string) =>
  new Response(JSON.stringify({ type: 'about:blank', title: 'Refused', status, code, detail }), {
    status,
    headers: { 'content-type': 'application/problem+json' },
  });

function open(handlers: Parameters<typeof mockApi>[0]) {
  const api = mockApi({
    'GET /me': () => ADMIN,
    'GET /admin/config': () => ({ groups: [] }),
    'GET /admin/runtime-settings': () => ({ overridesActive: 0, settings: [], switches: [] }),
    'GET /admin/discovery-hosts': () => ({ max: 500, hosts: [] }),
    'GET /admin/shadow-libraries': () => ({ max: 200, libraries: [] }),
    'GET /admin/known-targets': () => ({ max: 1000, targets: [] }),
    ...handlers,
  });
  return { api, ...renderAdmin('/admin/settings') };
}

const form = () => screen.getByRole('form', { name: 'Add a list' });

describe('Feature: an admin adds the GitHub lists and web pages the Scout reads (ADR 0012)', () => {
  it('Scenario: each added list shows who added it and why', async () => {
    const { container } = open({ 'GET /admin/list-seeds': () => SEED });

    const table = await screen.findByRole('table', { name: 'Added lists (1 of 50)' });
    expect(within(table).getByRole('rowheader', { name: 'acme/awesome-rag' })).toBeInTheDocument();
    expect(
      within(table).getByRole('rowheader', { name: 'acme/awesome-rag' }).closest('tr'),
    ).toHaveTextContent(/GitHub · by 42 · .* · the best RAG list/);
    await settle();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('Scenario: no list added says what the collector reads instead', async () => {
    open({ 'GET /admin/list-seeds': () => ({ max: 50, lists: [] }) });

    expect(
      await screen.findByText('No list added. The collector reads the lists its own search finds.'),
    ).toBeInTheDocument();
  });

  it('Scenario: an added list is sent with its reason, and the lists load again', async () => {
    let lists = SEED;
    const { api } = open({
      'GET /admin/list-seeds': () => lists,
      'POST /admin/list-seeds': () => {
        const added = entry('acme/awesome-agents', 'agents');
        lists = { ...lists, lists: [...lists.lists, added] };
        return json(added, 201);
      },
    });
    const user = userEvent.setup();
    await screen.findByRole('table', { name: 'Added lists (1 of 50)' });

    await user.type(
      within(form()).getByRole('textbox', { name: 'GitHub repository or web page' }),
      ' https://github.com/Acme/awesome-agents ',
    );
    await user.type(within(form()).getByRole('textbox', { name: 'Reason (optional)' }), 'agents');
    await user.click(screen.getByRole('button', { name: 'Add list' }));

    expect(
      await screen.findByText('Added: acme/awesome-agents is read at the next pass.'),
    ).toBeInTheDocument();
    expect(
      api.calls.find((c) => c.method === 'POST' && c.path === '/admin/list-seeds')?.body,
    ).toEqual({ address: 'https://github.com/Acme/awesome-agents', reason: 'agents' });
    expect(await screen.findByRole('table', { name: 'Added lists (2 of 50)' })).toBeInTheDocument();
    expect(
      within(form()).getByRole('textbox', { name: 'GitHub repository or web page' }),
    ).toHaveValue('');
  });

  it('Scenario: an empty address is not sent, and a refused one says why', async () => {
    const { api } = open({
      'GET /admin/list-seeds': () => SEED,
      'POST /admin/list-seeds': () =>
        refused(
          400,
          'invalid-request',
          'address: pages on coursera.org are never read: its terms forbid automated reading without written consent.',
        ),
    });
    const user = userEvent.setup();
    await screen.findByRole('table', { name: 'Added lists (1 of 50)' });

    await user.click(screen.getByRole('button', { name: 'Add list' }));
    expect(within(form()).getByRole('alert')).toHaveTextContent('Enter a GitHub repository');
    expect(
      within(form()).getByRole('textbox', { name: 'GitHub repository or web page' }),
    ).toHaveAttribute('aria-invalid', 'true');
    expect(api.calls.some((c) => c.method === 'POST')).toBe(false);

    await user.type(
      within(form()).getByRole('textbox', { name: 'GitHub repository or web page' }),
      'https://www.coursera.org/articles/ai',
    );
    await user.click(screen.getByRole('button', { name: 'Add list' }));
    expect(
      await screen.findByText(
        'Not saved: address: pages on coursera.org are never read: its terms forbid automated reading without written consent.',
      ),
    ).toBeInTheDocument();
  });

  it('Scenario: a web page is added, shown as a web page and removed by its id', async () => {
    let lists = SEED;
    const { api } = open({
      'GET /admin/list-seeds': () => lists,
      'POST /admin/list-seeds': () => {
        const added = entry(PAGE, 'a roundup');
        lists = { ...lists, lists: [...lists.lists, added] };
        return json(added, 201);
      },
      'POST /admin/list-seeds/6f1c3a52-8d0e-3b7a-9c41-2e5d7f8a9b10/remove': () => {
        lists = SEED;
        return undefined;
      },
    });
    const user = userEvent.setup();
    await screen.findByRole('table', { name: 'Added lists (1 of 50)' });

    await user.type(
      within(form()).getByRole('textbox', { name: 'GitHub repository or web page' }),
      PAGE,
    );
    await user.click(screen.getByRole('button', { name: 'Add list' }));

    expect(await screen.findByText(`Added: ${PAGE} is read at the next pass.`)).toBeInTheDocument();
    const table = await screen.findByRole('table', { name: 'Added lists (2 of 50)' });
    expect(within(table).getByRole('rowheader', { name: PAGE }).closest('tr')).toHaveTextContent(
      /Web page · by 42 · .* · a roundup/,
    );

    await user.click(screen.getByRole('button', { name: `Remove ${PAGE}` }));
    await user.click(
      within(screen.getByRole('group', { name: `Stop reading ${PAGE}?` })).getByRole('button', {
        name: 'Remove list',
      }),
    );

    expect(await screen.findByText(`Removed: ${PAGE}.`)).toBeInTheDocument();
    expect(
      api.calls.some(
        (c) => c.path === '/admin/list-seeds/6f1c3a52-8d0e-3b7a-9c41-2e5d7f8a9b10/remove',
      ),
    ).toBe(true);
  });

  it('Scenario: a list is removed only after a review that says what stays', async () => {
    let lists = SEED;
    const { api } = open({
      'GET /admin/list-seeds': () => lists,
      'POST /admin/list-seeds/0b6e2d4f-1a3c-3e5f-8a7b-9c0d1e2f3a4b/remove': () => {
        lists = { ...lists, lists: [] };
        return undefined;
      },
    });
    const user = userEvent.setup();
    await screen.findByRole('table', { name: 'Added lists (1 of 50)' });

    await user.click(screen.getByRole('button', { name: 'Remove acme/awesome-rag' }));
    const review = screen.getByRole('group', { name: 'Stop reading acme/awesome-rag?' });
    expect(within(review).getByRole('heading')).toHaveFocus();
    expect(within(review).getByText(/stay in the catalog/)).toBeInTheDocument();
    expect(api.calls.some((c) => c.method === 'POST')).toBe(false);

    await user.type(within(review).getByRole('textbox', { name: 'Reason (optional)' }), 'stale');
    await user.click(within(review).getByRole('button', { name: 'Remove list' }));

    expect(await screen.findByText('Removed: acme/awesome-rag.')).toBeInTheDocument();
    expect(api.calls.find((c) => c.method === 'POST')?.body).toEqual({ reason: 'stale' });
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: 'Added lists (0 of 50)' })).toHaveFocus(),
    );
  });

  it('Scenario: lists that cannot be loaded offer a retry', async () => {
    let fail = true;
    open({
      'GET /admin/list-seeds': () => {
        if (fail) return new Response('', { status: 503 });
        return SEED;
      },
    });
    const user = userEvent.setup();

    expect(await screen.findByText('Could not load the lists to read.')).toBeInTheDocument();
    fail = false;
    const panel = screen.getByRole('region', { name: 'Lists to read' });
    await user.click(within(panel).getByRole('button', { name: /again/i }));

    expect(await screen.findByRole('table', { name: 'Added lists (1 of 50)' })).toBeInTheDocument();
  });

  it('Scenario: UC-020 BR-008 a list discovery found says so and does not count against the added lists', async () => {
    const found: ListSeedEntry = {
      ...entry('https://futurepedia.io/best-ai-coding-tools'),
      origin: 'DISCOVERY',
      addedBy: 'discovery-coding',
    };
    const { container } = open({
      'GET /admin/list-seeds': () => ({ max: 50, lists: [...SEED.lists, found] }),
    });

    const table = await screen.findByRole('table', { name: 'Added lists (1 of 50)' });
    expect(
      within(table)
        .getByRole('rowheader', { name: 'https://futurepedia.io/best-ai-coding-tools' })
        .closest('tr'),
    ).toHaveTextContent(/Web page · found by discovery-coding on a directory host · /);
    expect(
      screen.getByText(/1 more list was found by discovery on a directory host/),
    ).toBeInTheDocument();
    await settle();
    expect(await axeViolations(container)).toEqual([]);
  });
});
