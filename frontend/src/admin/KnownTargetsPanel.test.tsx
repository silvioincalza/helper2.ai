import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { KnownTargetEntry, KnownTargets } from '@/api/types';
import { axeViolations } from '@/test/a11y';
import { ADMIN } from '@/test/fixtures';
import { json, mockApi } from '@/test/mockApi';
import { renderAdmin, settle } from '@/test/render';

const entry = (
  id: string,
  kind: KnownTargetEntry['kind'],
  name: string,
  otherNames: string[] = [],
  relationships = 0,
): KnownTargetEntry => ({
  id,
  kind,
  name,
  otherNames,
  addedBy: 'migration',
  addedAt: '2026-11-11T22:00:00Z',
  origin: 'CURATED',
  relationships,
});

const OPENAI = entry('11111111-1111-4111-8111-111111111111', 'VENDOR', 'OpenAI', ['Open AI'], 12);
const SLACK = entry('22222222-2222-4222-8222-222222222222', 'SERVICE', 'Slack', [], 3);
const SEED: KnownTargets = { max: 1000, targets: [OPENAI, SLACK] };

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
    'GET /admin/discovery-hosts': () => ({ max: 500, hosts: [] }),
    ...handlers,
  });
  return { api, ...renderAdmin('/admin/settings') };
}

describe('Feature: an admin manages the vendors and services a relationship may name (ADR 0019)', () => {
  it('Scenario: the two lists show each name with its other names and its relationships', async () => {
    const { container } = open({ 'GET /admin/known-targets': () => SEED });

    const vendors = await screen.findByRole('table', { name: 'Vendors (1)' });
    const openai = within(vendors).getByRole('row', { name: /OpenAI/ });
    expect(within(openai).getByText('Open AI')).toBeInTheDocument();
    expect(within(openai).getByText('12')).toBeInTheDocument();
    const services = screen.getByRole('table', { name: 'Services (1)' });
    expect(within(services).getByRole('rowheader', { name: 'Slack' })).toBeInTheDocument();
    await settle();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('Scenario: an added service is sent with its kind and its other names', async () => {
    let targets = SEED;
    const { api } = open({
      'GET /admin/known-targets': () => targets,
      'POST /admin/known-targets': () => {
        const added = entry('33333333-3333-4333-8333-333333333333', 'SERVICE', 'GitHub', ['GH']);
        targets = { ...targets, targets: [...targets.targets, added] };
        return json(added, 201);
      },
    });
    const user = userEvent.setup();
    await screen.findByRole('table', { name: 'Services (1)' });
    const form = screen.getByRole('form', { name: 'Add a vendor or a service' });

    await user.selectOptions(within(form).getByRole('combobox', { name: 'Kind' }), 'SERVICE');
    await user.type(within(form).getByRole('textbox', { name: 'Name' }), ' GitHub ');
    await user.type(
      within(form).getByRole('textbox', { name: /Other names/ }),
      'GH{Enter} {Enter}',
    );
    await user.click(within(form).getByRole('button', { name: 'Add name' }));

    expect(await screen.findByText('Added: GitHub.')).toBeInTheDocument();
    expect(api.calls.find((c) => c.method === 'POST')?.body).toEqual({
      kind: 'SERVICE',
      name: 'GitHub',
      otherNames: ['GH'],
    });
    expect(await screen.findByRole('table', { name: 'Services (2)' })).toBeInTheDocument();
  });

  it('Scenario: an empty name is caught before it is sent; a taken one says why', async () => {
    const { api } = open({
      'GET /admin/known-targets': () => SEED,
      'POST /admin/known-targets': () => duplicate('Open AI already means OpenAI.'),
    });
    const user = userEvent.setup();
    await screen.findByRole('table', { name: 'Vendors (1)' });
    const form = screen.getByRole('form', { name: 'Add a vendor or a service' });

    await user.click(within(form).getByRole('button', { name: 'Add name' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a name');
    expect(within(form).getByRole('textbox', { name: 'Name' })).toHaveAttribute(
      'aria-invalid',
      'true',
    );
    expect(api.calls.some((c) => c.method === 'POST')).toBe(false);

    await user.type(within(form).getByRole('textbox', { name: 'Name' }), 'Open AI');
    await user.click(within(form).getByRole('button', { name: 'Add name' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Not saved: Open AI already means OpenAI.',
    );
  });

  it('Scenario: a change starts from the stored names and sends the whole target', async () => {
    const { api } = open({
      'GET /admin/known-targets': () => SEED,
      [`PUT /admin/known-targets/${OPENAI.id}`]: () => ({ ...OPENAI, otherNames: [] }),
    });
    const user = userEvent.setup();
    await screen.findByRole('table', { name: 'Vendors (1)' });

    await user.click(screen.getByRole('button', { name: 'Change OpenAI' }));
    const form = screen.getByRole('form', { name: 'Change OpenAI' });
    expect(within(form).getByRole('heading')).toHaveFocus();
    expect(within(form).getByText(/goes back to the review list/)).toBeInTheDocument();
    const others = within(form).getByRole('textbox', { name: /Other names/ });
    expect(others).toHaveValue('Open AI');

    await user.clear(others);
    await user.click(within(form).getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Saved: OpenAI.')).toBeInTheDocument();
    expect(api.calls.find((c) => c.method === 'PUT')?.body).toEqual({
      kind: 'VENDOR',
      name: 'OpenAI',
      otherNames: [],
    });
  });

  it('keeps a name with a comma whole when a target is saved unchanged', async () => {
    const inc = entry('33333333-3333-4333-8333-333333333333', 'VENDOR', 'Example', [
      'Example, Inc.',
      'Example Labs',
    ]);
    const api = mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/known-targets': () => ({ max: 1000, targets: [inc] }),
      [`PUT /admin/known-targets/${inc.id}`]: () => inc,
    });
    const user = userEvent.setup();
    renderAdmin('/admin/settings');

    await user.click(await screen.findByRole('button', { name: 'Change Example' }));
    const form = screen.getByRole('form', { name: 'Change Example' });
    expect(within(form).getByRole('textbox', { name: /Other names/ })).toHaveValue(
      'Example, Inc.\nExample Labs',
    );
    await user.click(within(form).getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Saved: Example.')).toBeInTheDocument();
    expect(api.calls.find((c) => c.method === 'PUT')?.body).toEqual({
      kind: 'VENDOR',
      name: 'Example',
      otherNames: ['Example, Inc.', 'Example Labs'],
    });
  });

  it('Scenario: a removal says how many relationships go back to review, then asks', async () => {
    let targets = SEED;
    const { api } = open({
      'GET /admin/known-targets': () => targets,
      [`POST /admin/known-targets/${SLACK.id}/remove`]: () => {
        targets = { ...targets, targets: [OPENAI] };
        return undefined;
      },
    });
    const user = userEvent.setup();
    await screen.findByRole('table', { name: 'Services (1)' });

    await user.click(screen.getByRole('button', { name: 'Remove Slack' }));
    const review = screen.getByRole('group', { name: 'Remove Slack?' });
    expect(within(review).getByRole('heading')).toHaveFocus();
    expect(within(review).getByText(/3 relationships carry it/)).toBeInTheDocument();
    expect(api.calls.some((c) => c.method === 'POST')).toBe(false);

    await user.click(within(review).getByRole('button', { name: 'Remove name' }));

    expect(await screen.findByText('Removed: Slack.')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('None yet.')).toBeInTheDocument());
  });

  it('Scenario: a list that cannot load offers a retry', async () => {
    open({
      'GET /admin/known-targets': () =>
        new Response('{}', { status: 500, headers: { 'content-type': 'application/json' } }),
    });
    expect(
      await screen.findByText('Could not load the known vendors and services.'),
    ).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /try again/i }).length).toBeGreaterThan(0);
  });
});
