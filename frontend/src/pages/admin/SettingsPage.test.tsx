import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN } from '@/test/fixtures';
import { axeViolations } from '@/test/a11y';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp } from '@/test/render';
import type { EffectiveConfig } from '@/api/types';

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
          label: 'Tavily credits a day, all of Helper2',
          kind: 'CAP',
          value: null,
          unit: 'credits a day',
          variable: 'H2_TAVILY_GLOBAL_DAILY_CREDITS',
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

const row = (table: HTMLElement, label: string) =>
  within(table).getByRole('rowheader', { name: label }).closest('tr') as HTMLElement;

describe('Feature: the Settings page shows what the app runs with now', () => {
  it('Scenario: each group is a table, each value in words, with the variable that sets it', async () => {
    mockApi({ 'GET /me': () => ADMIN, 'GET /admin/config': () => CONFIG });
    const { container } = renderApp('/admin/settings');

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
      within(row(search, 'Tavily credits a day, all of Helper2')).getByText('No cap'),
    ).toBeInTheDocument();
    expect(within(row(search, 'Query translation on')).getByText('On')).toBeInTheDocument();
    expect(
      within(row(search, 'Query translation on')).getByText('No variable'),
    ).toBeInTheDocument();
    expect(within(row(search, 'Sources on')).getByText('arxiv, npm')).toBeInTheDocument();
    expect(
      within(row(search, 'Translations a day')).getByText('H2_SEARCH_DAILY_TRANSLATIONS'),
    ).toBeInTheDocument();

    const part = screen.getByRole('region', { name: 'Settings' });
    expect(within(part).queryByRole('button')).not.toBeInTheDocument();
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
      'GET /me': () => ADMIN,
      'GET /admin/config': () => (fail ? problem(503, 'Service Unavailable') : CONFIG),
    });
    renderApp('/admin/settings');
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
