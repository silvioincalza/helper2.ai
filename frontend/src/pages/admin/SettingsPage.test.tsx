import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN } from '@/test/fixtures';
import { axeViolations } from '@/test/a11y';
import { mockApi, problem } from '@/test/mockApi';
import { renderAdmin } from '@/test/render';
import type { EffectiveConfig, RuntimeSettingState, RuntimeSettings } from '@/api/types';

const CONFIG: EffectiveConfig = {
  groups: [
    {
      id: 'models',
      title: 'Models',
      settings: [
        {
          key: 'models.reasoning',
          label: 'Reasoning model',
          kind: 'MODEL',
          value: 'nvidia/nemotron-3-super-120b-a12b',
          unit: null,
          variable: 'H2_NEBIUS_REASONING_MODEL',
        },
        {
          key: 'models.fast',
          label: 'Fast model',
          kind: 'MODEL',
          value: null,
          unit: null,
          variable: 'H2_NEBIUS_FAST_MODEL',
        },
      ],
    },
    {
      id: 'search',
      title: 'Search',
      settings: [
        {
          key: 'search.dailyTranslations',
          label: 'Translations a day',
          kind: 'COUNT',
          value: '300',
          unit: 'calls a day',
          variable: 'H2_SEARCH_DAILY_TRANSLATIONS',
        },
        {
          key: 'search.webGlobalDailyCredits',
          label: 'Live web credits a day, all of Helper2',
          kind: 'CAP',
          value: null,
          unit: 'credits a day',
          variable: 'H2_LIVE_WEB_DAILY_CREDITS',
        },
        {
          key: 'search.translationOn',
          label: 'Query translation on',
          kind: 'FLAG',
          value: 'true',
          unit: null,
          variable: null,
        },
        {
          key: 'search.sourcesOn',
          label: 'Sources on',
          kind: 'NAMES',
          value: 'arxiv,npm',
          unit: null,
          variable: 'H2_SOURCE_<ID>_ENABLED',
        },
      ],
    },
  ],
};

/** The discovery host lists of the same page (ADR 0022): empty, so they add nothing to these scenarios. */
const NO_HOSTS = { 'GET /admin/discovery-hosts': () => ({ max: 500, hosts: [] }) };

const GUIDE: RuntimeSettingState = {
  key: 'guide.dailyModelCalls',
  label: 'Model calls a day, all visitors',
  unit: 'calls a day',
  help: "The AI guide's model calls a UTC day for everyone. 0: answers come from search only.",
  min: 0,
  max: 3000,
  variable: 'H2_GUIDE_DAILY_MODEL_CALLS',
  defaultValue: 600,
  value: 600,
  overridden: false,
  inUse: true,
  version: 0,
  history: [],
};
const CREDITS: RuntimeSettingState = {
  key: 'search.webGlobalDailyCredits',
  label: 'Live web credits a day, all of Helper2',
  unit: 'credits a day',
  help: 'Live web calls a UTC day for all of Helper2. 0: no live call, stored results only.',
  min: 0,
  max: 2000,
  variable: 'H2_LIVE_WEB_DAILY_CREDITS',
  defaultValue: null,
  value: 120,
  overridden: true,
  inUse: true,
  version: 7,
  history: [
    {
      version: 7,
      value: 120,
      changedBy: '12345',
      changedAt: '2026-11-05T10:00:00Z',
      reason: 'rotating IPs',
    },
    { version: 3, value: 80, changedBy: '12345', changedAt: '2026-11-04T09:00:00Z', reason: null },
  ],
};
const runtime = (...settings: RuntimeSettingState[]): RuntimeSettings => ({
  overridesActive: settings.filter((s) => s.overridden).length,
  settings,
});
const versionConflict = () =>
  new Response(
    JSON.stringify({
      type: 'https://helper2.ai/problems/version-conflict',
      title: 'Changed by someone else',
      status: 409,
      code: 'version-conflict',
    }),
    { status: 409, headers: { 'content-type': 'application/problem+json' } },
  );
const limitRow = (label: string) =>
  within(screen.getByRole('table', { name: 'Spending limits you can change' }))
    .getByRole('rowheader', { name: new RegExp(`^${label}`) })
    .closest('tr') as HTMLElement;
const GUIDE_LABEL = 'Model calls a day, all visitors';

const row = (table: HTMLElement, label: string) =>
  within(table).getByRole('rowheader', { name: label }).closest('tr') as HTMLElement;

describe('Feature: the Settings page shows what the app runs with now', () => {
  it('Scenario: each group is a table, each value in words, with the variable that sets it', async () => {
    mockApi({
      ...NO_HOSTS,
      'GET /me': () => ADMIN,
      'GET /admin/config': () => CONFIG,
      'GET /admin/runtime-settings': () => runtime(GUIDE, CREDITS),
    });
    const { container } = renderAdmin('/admin/settings');

    const models = await screen.findByRole('table', { name: 'Models' });
    expect(
      within(row(models, 'Reasoning model')).getByText('nvidia/nemotron-3-super-120b-a12b'),
    ).toBeInTheDocument();
    expect(within(row(models, 'Fast model')).getByText('None')).toBeInTheDocument();

    const search = screen.getByRole('table', { name: 'Search' });
    expect(
      within(row(search, 'Translations a day')).getByText('300 calls a day'),
    ).toBeInTheDocument();
    expect(
      within(row(search, 'Live web credits a day, all of Helper2')).getByText(
        '120 credits a day (changed at runtime)',
      ),
    ).toBeInTheDocument();
    expect(within(row(search, 'Query translation on')).getByText('On')).toBeInTheDocument();
    expect(
      within(row(search, 'Query translation on')).getByText('No variable'),
    ).toBeInTheDocument();
    expect(within(row(search, 'Sources on')).getByText('arxiv, npm')).toBeInTheDocument();
    expect(
      within(row(search, 'Translations a day')).getByText('H2_SEARCH_DAILY_TRANSLATIONS'),
    ).toBeInTheDocument();

    expect(
      within(models).queryByRole('button'),
      'the read-only tables have no action',
    ).not.toBeInTheDocument();
    expect(
      within(screen.getByRole('navigation', { name: 'Back office' })).getByRole('link', {
        name: 'Settings',
      }),
    ).toHaveAttribute('aria-current', 'page');
    expect(await axeViolations(container)).toEqual([]);
  });

  it('Scenario: a failed load offers Try again and keeps focus on the heading', async () => {
    let fail = true;
    mockApi({
      ...NO_HOSTS,
      'GET /me': () => ADMIN,
      'GET /admin/config': () => (fail ? problem(503, 'Service Unavailable') : CONFIG),
      'GET /admin/runtime-settings': () => runtime(GUIDE),
    });
    renderAdmin('/admin/settings');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the settings.');
    fail = false;
    await userEvent.click(within(alert).getByRole('button', { name: /Try again/ }));
    expect(await screen.findByRole('table', { name: 'Models' })).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 2, name: 'Settings' })).toHaveFocus(),
    );
  });
});

describe('Feature: an administrator changes a spending limit without a deploy', () => {
  it('Scenario: each limit shows its value now, its default, its range and one line of help', async () => {
    mockApi({
      ...NO_HOSTS,
      'GET /me': () => ADMIN,
      'GET /admin/config': () => CONFIG,
      'GET /admin/runtime-settings': () => runtime(GUIDE, CREDITS),
    });
    renderAdmin('/admin/settings');
    await screen.findByRole('table', { name: 'Spending limits you can change' });
    const guide = limitRow(GUIDE_LABEL);
    expect(within(guide).getAllByText('600 calls a day'), 'now and the default').toHaveLength(2);
    expect(within(guide).getByText('Default')).toHaveClass('h2-status-pill--ok');
    expect(within(guide).getByText('0 to 3000 calls a day')).toBeInTheDocument();
    expect(within(guide).getByText(/0: answers come from search only/)).toBeInTheDocument();
    expect(
      within(guide).queryByRole('button', { name: /Reset to default/ }),
      'nothing to reset at the default',
    ).not.toBeInTheDocument();

    const credits = limitRow('Live web credits a day');
    expect(within(credits).getByText('Changed')).toHaveClass('h2-status-pill--next');
    expect(within(credits).getByText(/^No cap/)).toBeInTheDocument();
  });

  it('Scenario: a change is reviewed old to new, confirmed with a reason, and sent with its version', async () => {
    let state = GUIDE;
    const m = mockApi({
      ...NO_HOSTS,
      'GET /me': () => ADMIN,
      'GET /admin/config': () => CONFIG,
      'GET /admin/runtime-settings': () => runtime(state),
      'PUT /admin/runtime-settings/guide.dailyModelCalls': ({ body }) => {
        const b = body as { value: number };
        state = { ...GUIDE, value: b.value, overridden: true, version: 8 };
        return state;
      },
    });
    renderAdmin('/admin/settings');
    await userEvent.click(await screen.findByRole('button', { name: `Change: ${GUIDE_LABEL}` }));
    const input = screen.getByRole('spinbutton', { name: `New value for ${GUIDE_LABEL}` });
    await userEvent.clear(input);
    await userEvent.type(input, '50');
    await userEvent.click(screen.getByRole('button', { name: 'Review changes' }));

    const review = screen.getByRole('group', { name: 'Review changes' });
    expect(review).toHaveTextContent(`${GUIDE_LABEL}: 600 calls a day → 50 calls a day`);
    expect(screen.getByRole('heading', { name: 'Review changes' })).toHaveFocus();
    await userEvent.type(within(review).getByRole('textbox', { name: /Reason/ }), 'spike');
    await userEvent.click(within(review).getByRole('button', { name: 'Confirm change' }));

    expect(await screen.findByText(`Saved: ${GUIDE_LABEL} is now 50 calls a day.`)).toHaveAttribute(
      'role',
      'status',
    );
    expect(m.callsTo('PUT', '/admin/runtime-settings/guide.dailyModelCalls')[0]?.body).toEqual({
      value: 50,
      version: 0,
      reason: 'spike',
    });
    expect(await within(limitRow(GUIDE_LABEL)).findByText('Changed')).toBeInTheDocument();
  });

  it('Scenario: a value outside the range is blocked in the form, nothing is sent', async () => {
    const m = mockApi({
      ...NO_HOSTS,
      'GET /me': () => ADMIN,
      'GET /admin/config': () => CONFIG,
      'GET /admin/runtime-settings': () => runtime(GUIDE),
    });
    renderAdmin('/admin/settings');
    await userEvent.click(await screen.findByRole('button', { name: `Change: ${GUIDE_LABEL}` }));
    const input = screen.getByRole('spinbutton', { name: `New value for ${GUIDE_LABEL}` });
    await userEvent.clear(input);
    await userEvent.type(input, '3001');
    await userEvent.click(screen.getByRole('button', { name: 'Review changes' }));

    expect(screen.getByRole('alert')).toHaveTextContent('Enter a whole number from 0 to 3000.');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(screen.queryByRole('group', { name: 'Review changes' })).not.toBeInTheDocument();
    expect(m.callsTo('PUT', '/admin/runtime-settings/guide.dailyModelCalls')).toHaveLength(0);
  });

  it('Scenario: someone changed it first; the page says so and shows the current value', async () => {
    let loads = 0;
    mockApi({
      ...NO_HOSTS,
      'GET /me': () => ADMIN,
      'GET /admin/config': () => CONFIG,
      'GET /admin/runtime-settings': () =>
        loads++ === 0
          ? runtime(GUIDE)
          : runtime({ ...GUIDE, value: 10, overridden: true, version: 9 }),
      'PUT /admin/runtime-settings/guide.dailyModelCalls': versionConflict,
    });
    renderAdmin('/admin/settings');
    await userEvent.click(await screen.findByRole('button', { name: `Change: ${GUIDE_LABEL}` }));
    await userEvent.click(screen.getByRole('button', { name: 'Review changes' }));
    await userEvent.click(screen.getByRole('button', { name: 'Confirm change' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Someone changed this limit first.');
    expect(await within(limitRow(GUIDE_LABEL)).findByText('10 calls a day')).toBeInTheDocument();
  });

  it('Scenario: History restores an earlier value through the same review, and Reset goes back to the default', async () => {
    const m = mockApi({
      ...NO_HOSTS,
      'GET /me': () => ADMIN,
      'GET /admin/config': () => CONFIG,
      'GET /admin/runtime-settings': () => runtime(CREDITS),
      'PUT /admin/runtime-settings/search.webGlobalDailyCredits': () => ({
        ...CREDITS,
        value: 80,
      }),
      'POST /admin/runtime-settings/search.webGlobalDailyCredits/reset': () => ({
        ...CREDITS,
        value: null,
        overridden: false,
      }),
    });
    const { container } = renderAdmin('/admin/settings');
    await userEvent.click(await screen.findByText('History (2)'));
    expect(screen.getByText(/120 credits a day · by 12345/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /^Restore 80 credits a day from/ }));
    expect(screen.getByRole('group', { name: 'Review changes' })).toHaveTextContent(
      '120 credits a day → 80 credits a day',
    );
    expect(await axeViolations(container)).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: 'Confirm change' }));
    await waitFor(() =>
      expect(
        m.callsTo('PUT', '/admin/runtime-settings/search.webGlobalDailyCredits')[0]?.body,
      ).toEqual({ value: 80, version: 7, reason: null }),
    );

    await userEvent.click(
      await screen.findByRole('button', {
        name: 'Reset to default: Live web credits a day, all of Helper2',
      }),
    );
    expect(screen.getByRole('group', { name: 'Review changes' })).toHaveTextContent(
      '120 credits a day → No cap (Default)',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Confirm change' }));
    await waitFor(() =>
      expect(
        m.callsTo('POST', '/admin/runtime-settings/search.webGlobalDailyCredits/reset')[0]?.body,
      ).toEqual({ version: 7, reason: null }),
    );
  });
});
