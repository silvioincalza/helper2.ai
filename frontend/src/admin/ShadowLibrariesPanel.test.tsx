import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ShadowLibraries, ShadowLibraryEntry } from '@/api/types';
import { axeViolations } from '@/test/a11y';
import { ADMIN } from '@/test/fixtures';
import { json, mockApi } from '@/test/mockApi';
import { renderAdmin, settle } from '@/test/render';

const entry = (name: string, reason: string | null = null): ShadowLibraryEntry => ({
  name,
  addedBy: 'migration',
  addedAt: '2026-11-18T03:00:00Z',
  reason,
});

const SEED: ShadowLibraries = {
  max: 200,
  libraries: [entry('libgen'), entry('z-lib', 'pirated books')],
};

const refusal = (status: number, code: string, detail: string) =>
  new Response(JSON.stringify({ type: 'about:blank', title: code, status, code, detail }), {
    status,
    headers: { 'content-type': 'application/problem+json' },
  });

function open(handlers: Parameters<typeof mockApi>[0]) {
  const api = mockApi({
    'GET /me': () => ADMIN,
    'GET /admin/config': () => ({ groups: [] }),
    'GET /admin/runtime-settings': () => ({ overridesActive: 0, settings: [] }),
    'GET /admin/discovery-hosts': () => ({ max: 500, hosts: [] }),
    'GET /admin/known-targets': () => ({ max: 1000, targets: [] }),
    ...handlers,
  });
  return { api, ...renderAdmin('/admin/settings') };
}

const addForm = () => screen.getByRole('form', { name: 'Add a shadow library' });

describe('Feature: an admin keeps the list of shadow libraries no search shows (ADR 0049)', () => {
  it('Scenario: the list shows each name with who added it and why', async () => {
    const { container } = open({ 'GET /admin/shadow-libraries': () => SEED });

    const list = await screen.findByRole('table', { name: 'Names (2 of 200)' });
    expect(within(list).getByRole('rowheader', { name: 'libgen' })).toBeInTheDocument();
    expect(within(list).getByText(/pirated books/)).toBeInTheDocument();
    await settle();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('Scenario: an added name is sent with its reason, and the list loads again', async () => {
    let libraries = SEED;
    const { api } = open({
      'GET /admin/shadow-libraries': () => libraries,
      'POST /admin/shadow-libraries': () => {
        const added = entry('sci-hub', 'papers without a licence');
        libraries = { ...libraries, libraries: [...libraries.libraries, added] };
        return json(added, 201);
      },
    });
    const user = userEvent.setup();
    await screen.findByRole('table', { name: 'Names (2 of 200)' });

    await user.type(within(addForm()).getByRole('textbox', { name: 'Name' }), ' Sci-Hub ');
    await user.type(
      within(addForm()).getByRole('textbox', { name: 'Reason (optional)' }),
      'papers without a licence',
    );
    await user.click(screen.getByRole('button', { name: 'Add shadow library' }));

    expect(
      await screen.findByText('Added: results on sci-hub sites are no longer shown.'),
    ).toBeInTheDocument();
    expect(
      api.calls.find((c) => c.method === 'POST' && c.path === '/admin/shadow-libraries')?.body,
    ).toEqual({ name: 'Sci-Hub', reason: 'papers without a licence' });
    expect(await screen.findByRole('table', { name: 'Names (3 of 200)' })).toBeInTheDocument();
  });

  it('Scenario: an empty name is caught before it is sent; a refused one says why', async () => {
    const { api } = open({
      'GET /admin/shadow-libraries': () => SEED,
      'POST /admin/shadow-libraries': () =>
        refusal(
          400,
          'invalid-request',
          'name: one part of a host name, such as libgen: letters, digits and hyphens, no dot.',
        ),
    });
    const user = userEvent.setup();
    await screen.findByRole('table', { name: 'Names (2 of 200)' });

    await user.click(screen.getByRole('button', { name: 'Add shadow library' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a name, such as libgen.');
    const input = within(addForm()).getByRole('textbox', { name: 'Name' });
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(api.calls.some((c) => c.method === 'POST')).toBe(false);

    await user.type(input, 'libgen.rs');
    await user.click(screen.getByRole('button', { name: 'Add shadow library' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Not saved: name: one part of a host name',
    );
  });

  it('Scenario: a name is removed only after a review, with its reason', async () => {
    let libraries = SEED;
    const { api } = open({
      'GET /admin/shadow-libraries': () => libraries,
      'POST /admin/shadow-libraries/z-lib/remove': () => {
        libraries = {
          ...libraries,
          libraries: libraries.libraries.filter((l) => l.name !== 'z-lib'),
        };
        return undefined;
      },
    });
    const user = userEvent.setup();
    await screen.findByRole('table', { name: 'Names (2 of 200)' });

    await user.click(screen.getByRole('button', { name: 'Remove z-lib' }));
    const review = screen.getByRole('group', { name: 'Remove z-lib?' });
    expect(within(review).getByRole('heading')).toHaveFocus();
    expect(api.calls.some((c) => c.method === 'POST')).toBe(false);

    await user.type(
      within(review).getByRole('textbox', { name: 'Reason (optional)' }),
      'a mistake',
    );
    await user.click(within(review).getByRole('button', { name: 'Remove name' }));

    expect(
      await screen.findByText('Removed: results on z-lib sites can be shown again.'),
    ).toBeInTheDocument();
    expect(api.calls.find((c) => c.method === 'POST')?.body).toEqual({ reason: 'a mistake' });
    await waitFor(() =>
      expect(screen.getByRole('table', { name: 'Names (1 of 200)' })).toBeInTheDocument(),
    );
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: 'Names (1 of 200)' })).toHaveFocus(),
    );
  });

  it('Scenario: Cancel closes the review and gives focus back to the Remove button', async () => {
    open({ 'GET /admin/shadow-libraries': () => SEED });
    const user = userEvent.setup();
    await screen.findByRole('table', { name: 'Names (2 of 200)' });
    await user.click(screen.getByRole('button', { name: 'Remove z-lib' }));
    const review = screen.getByRole('group', { name: 'Remove z-lib?' });
    await user.click(within(review).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('group', { name: 'Remove z-lib?' })).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Remove z-lib' })).toHaveFocus());
  });

  it('Scenario: a list that cannot load offers a retry', async () => {
    open({
      'GET /admin/shadow-libraries': () =>
        new Response('{}', { status: 500, headers: { 'content-type': 'application/json' } }),
    });
    expect(await screen.findByText('Could not load the shadow libraries.')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /try again/i }).length).toBeGreaterThan(0);
  });
});
