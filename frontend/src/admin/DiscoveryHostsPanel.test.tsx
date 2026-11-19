import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { DiscoveryHostEntry, DiscoveryHosts } from '@/api/types';
import { axeViolations } from '@/test/a11y';
import { ADMIN } from '@/test/fixtures';
import { json, mockApi } from '@/test/mockApi';
import { renderAdmin, settle } from '@/test/render';

const entry = (
  host: string,
  kind: DiscoveryHostEntry['kind'],
  reason: string | null = null,
): DiscoveryHostEntry => ({
  host,
  kind,
  addedBy: 'migration',
  addedAt: '2026-11-06T22:00:00Z',
  reason,
});

const SEED: DiscoveryHosts = {
  max: 500,
  hosts: [entry('medium.com', 'WRITING'), entry('producthunt.com', 'DIRECTORY', 'lists products')],
};

const duplicate = (detail: string) =>
  new Response(
    JSON.stringify({
      type: 'about:blank',
      title: 'Duplicate',
      status: 409,
      code: 'duplicate',
      detail,
    }),
    { status: 409, headers: { 'content-type': 'application/problem+json' } },
  );

function open(handlers: Parameters<typeof mockApi>[0]) {
  const api = mockApi({
    'GET /me': () => ADMIN,
    'GET /admin/config': () => ({ groups: [] }),
    'GET /admin/runtime-settings': () => ({ overridesActive: 0, settings: [] }),
    'GET /admin/list-seeds': () => ({ max: 50, lists: [] }),
    'GET /admin/shadow-libraries': () => ({ max: 200, libraries: [] }),
    'GET /admin/known-targets': () => ({ max: 1000, targets: [] }),
    ...handlers,
  });
  return { api, ...renderAdmin('/admin/settings') };
}

describe('Feature: an admin manages the hosts discovery knows by name (ADR 0013)', () => {
  it('Scenario: the two lists show each host with who added it and why', async () => {
    const { container } = open({ 'GET /admin/discovery-hosts': () => SEED });

    const writing = await screen.findByRole('table', { name: 'Writing sites (1)' });
    expect(within(writing).getByRole('rowheader', { name: 'medium.com' })).toBeInTheDocument();
    const directories = screen.getByRole('table', { name: 'Directories (1)' });
    expect(within(directories).getByText(/lists products/)).toBeInTheDocument();
    await settle();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('Scenario: an added host is sent with its list and reason, and the lists load again', async () => {
    let hosts = SEED;
    const { api } = open({
      'GET /admin/discovery-hosts': () => hosts,
      'POST /admin/discovery-hosts': () => {
        const added = entry('techcrunch.com', 'WRITING', 'tech news');
        hosts = { ...hosts, hosts: [...hosts.hosts, added] };
        return json(added, 201);
      },
    });
    const user = userEvent.setup();
    await screen.findByRole('table', { name: 'Writing sites (1)' });

    await user.type(screen.getByRole('textbox', { name: 'Host' }), ' TechCrunch.com ');
    await user.type(
      within(screen.getByRole('form', { name: 'Add a host' })).getByRole('textbox', {
        name: 'Reason (optional)',
      }),
      'tech news',
    );
    await user.click(screen.getByRole('button', { name: 'Add host' }));

    expect(
      await screen.findByText('Added: techcrunch.com is now in Writing sites.'),
    ).toBeInTheDocument();
    expect(api.calls.find((c) => c.method === 'POST')?.body).toEqual({
      host: 'TechCrunch.com',
      kind: 'WRITING',
      reason: 'tech news',
    });
    expect(await screen.findByRole('table', { name: 'Writing sites (2)' })).toBeInTheDocument();
  });

  it('Scenario: an empty host is caught before it is sent; a refused one says why', async () => {
    const { api } = open({
      'GET /admin/discovery-hosts': () => SEED,
      'POST /admin/discovery-hosts': () =>
        duplicate('medium.com is in the writing list already. Remove it there first to move it.'),
    });
    const user = userEvent.setup();
    await screen.findByRole('table', { name: 'Writing sites (1)' });

    await user.click(screen.getByRole('button', { name: 'Add host' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a host name');
    expect(screen.getByRole('textbox', { name: 'Host' })).toHaveAttribute('aria-invalid', 'true');
    expect(api.calls.some((c) => c.method === 'POST')).toBe(false);

    await user.type(screen.getByRole('textbox', { name: 'Host' }), 'medium.com');
    await user.selectOptions(screen.getByRole('combobox', { name: 'List' }), 'DIRECTORY');
    await user.click(screen.getByRole('button', { name: 'Add host' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Not saved: medium.com is in the writing list already.',
    );
  });

  it('Scenario: a host is removed only after a review, with its reason', async () => {
    let hosts = SEED;
    const { api } = open({
      'GET /admin/discovery-hosts': () => hosts,
      'POST /admin/discovery-hosts/producthunt.com/remove': () => {
        hosts = { ...hosts, hosts: hosts.hosts.filter((h) => h.host !== 'producthunt.com') };
        return undefined;
      },
    });
    const user = userEvent.setup();
    await screen.findByRole('table', { name: 'Directories (1)' });

    await user.click(screen.getByRole('button', { name: 'Remove producthunt.com' }));
    const review = screen.getByRole('group', { name: 'Remove producthunt.com from Directories?' });
    expect(within(review).getByRole('heading')).toHaveFocus();
    expect(api.calls.some((c) => c.method === 'POST')).toBe(false);

    await user.type(within(review).getByRole('textbox', { name: 'Reason (optional)' }), 'real');
    await user.click(within(review).getByRole('button', { name: 'Remove host' }));

    expect(await screen.findByText('Removed: producthunt.com.')).toBeInTheDocument();
    expect(api.calls.find((c) => c.method === 'POST')?.body).toEqual({ reason: 'real' });
    await waitFor(() => expect(screen.getByText('No hosts in this list.')).toBeInTheDocument());
  });

  it('Scenario: the lists stay on screen while they load again', async () => {
    let hosts = SEED;
    open({
      'GET /admin/discovery-hosts': async () => {
        await new Promise((r) => setTimeout(r, 120));
        return hosts;
      },
      'POST /admin/discovery-hosts': () => {
        const added = entry('techcrunch.com', 'WRITING');
        hosts = { ...hosts, hosts: [...hosts.hosts, added] };
        return json(added, 201);
      },
    });
    const user = userEvent.setup();
    await screen.findByRole('table', { name: 'Writing sites (1)' });

    await user.type(screen.getByRole('textbox', { name: 'Host' }), 'techcrunch.com');
    await user.click(screen.getByRole('button', { name: 'Add host' }));

    expect(
      await screen.findByText('Added: techcrunch.com is now in Writing sites.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Writing sites (1)' })).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Directories (1)' })).toBeInTheDocument();
    expect(await screen.findByRole('table', { name: 'Writing sites (2)' })).toBeInTheDocument();
  });

  it('Scenario: after a remove, focus goes to the list heading or back to the Remove button', async () => {
    let hosts = SEED;
    let refuse = false;
    open({
      'GET /admin/discovery-hosts': async () => {
        await new Promise((r) => setTimeout(r, 120));
        return hosts;
      },
      'POST /admin/discovery-hosts/producthunt.com/remove': () => {
        if (refuse) return duplicate('producthunt.com changed meanwhile');
        hosts = { ...hosts, hosts: hosts.hosts.filter((h) => h.host !== 'producthunt.com') };
        return undefined;
      },
    });
    const user = userEvent.setup();
    await screen.findByRole('table', { name: 'Directories (1)' });

    refuse = true;
    await user.click(screen.getByRole('button', { name: 'Remove producthunt.com' }));
    await user.click(screen.getByRole('button', { name: 'Remove host' }));
    expect(
      await screen.findByText('Not saved: producthunt.com changed meanwhile'),
    ).toBeInTheDocument();
    await waitFor(
      () =>
        expect(document.activeElement).toBe(
          screen.getByRole('button', { name: 'Remove producthunt.com' }),
        ),
      { timeout: 2000 },
    );

    refuse = false;
    await user.click(screen.getByRole('button', { name: 'Remove producthunt.com' }));
    await user.click(screen.getByRole('button', { name: 'Remove host' }));
    await waitFor(
      () =>
        expect(document.activeElement).toBe(
          screen.getByRole('heading', { name: 'Directories (0)' }),
        ),
      { timeout: 2000 },
    );
  });

  it('Scenario: Cancel closes the review and gives focus back to the Remove button', async () => {
    open({ 'GET /admin/discovery-hosts': () => SEED });
    const user = userEvent.setup();
    await screen.findByRole('table', { name: 'Writing sites (1)' });
    await user.click(screen.getByRole('button', { name: 'Remove medium.com' }));
    const review = screen.getByRole('group', { name: 'Remove medium.com from Writing sites?' });
    await user.click(within(review).getByRole('button', { name: 'Cancel' }));
    expect(
      screen.queryByRole('group', { name: 'Remove medium.com from Writing sites?' }),
    ).not.toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Remove medium.com' })).toHaveFocus(),
    );
  });

  it('Scenario: while a change is sent, the buttons stay reachable and send nothing twice', async () => {
    let release = () => {};
    const sent = new Promise<void>((r) => {
      release = r;
    });
    const { api } = open({
      'GET /admin/discovery-hosts': () => SEED,
      'POST /admin/discovery-hosts': async () => {
        await sent;
        return json(entry('techcrunch.com', 'WRITING'), 201);
      },
    });
    const user = userEvent.setup();
    await screen.findByRole('table', { name: 'Writing sites (1)' });

    await user.type(screen.getByRole('textbox', { name: 'Host' }), 'techcrunch.com');
    const add = screen.getByRole('button', { name: 'Add host' });
    const remove = screen.getByRole('button', { name: 'Remove medium.com' });
    await user.click(add);

    await waitFor(() => expect(add).toHaveAttribute('aria-disabled', 'true'));
    expect(add).toBeEnabled();
    expect(remove).toHaveAttribute('aria-disabled', 'true');
    expect(remove).toBeEnabled();
    await user.click(add);
    await user.click(remove);
    expect(screen.queryByRole('group', { name: /Remove medium.com/ })).not.toBeInTheDocument();
    expect(api.calls.filter((c) => c.method === 'POST')).toHaveLength(1);

    release();
    expect(
      await screen.findByText('Added: techcrunch.com is now in Writing sites.'),
    ).toBeInTheDocument();
    expect(add).not.toHaveAttribute('aria-disabled');
  });

  it('Scenario: a second submit sent before the page redraws sends nothing', async () => {
    const { api } = open({
      'GET /admin/discovery-hosts': () => SEED,
      'POST /admin/discovery-hosts': () => json(entry('techcrunch.com', 'WRITING'), 201),
    });
    const user = userEvent.setup();
    await screen.findByRole('table', { name: 'Writing sites (1)' });
    await user.type(screen.getByRole('textbox', { name: 'Host' }), 'techcrunch.com');
    const form = screen.getByRole('form', { name: 'Add a host' });

    act(() => {
      fireEvent.submit(form);
      fireEvent.submit(form);
    });

    expect(
      await screen.findByText('Added: techcrunch.com is now in Writing sites.'),
    ).toBeInTheDocument();
    expect(api.calls.filter((c) => c.method === 'POST')).toHaveLength(1);
  });

  it('Scenario: lists that cannot load offer a retry', async () => {
    open({
      'GET /admin/discovery-hosts': () =>
        new Response('{}', { status: 500, headers: { 'content-type': 'application/json' } }),
    });
    expect(await screen.findByText('Could not load the discovery hosts.')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /try again/i }).length).toBeGreaterThan(0);
  });
});
