import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axeViolations } from '@/test/a11y';
import { POLICY } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

const DAY = 86_400;

/** Numbers no page ever typed, so a test can tell a number from the API from a typed one. */
const CHANGED = {
  guide: { maxCandidates: 40, maxPicks: 5 },
  dailyLimits: {
    guideQuestions: 50,
    webSearches: { guest: { search: 4, guide: 6 }, registered: { search: 10, guide: 12 } },
  },
  retention: {
    cachedSearchesSeconds: 2 * DAY,
    cachedGuideAnswersSeconds: 36 * 3600,
    translationsSeconds: 3 * DAY,
    queryVectorsSeconds: 14 * DAY,
    storedWebResultsSeconds: 10 * DAY,
    inactiveAccountsSeconds: 547 * DAY,
    handledReportsSeconds: 6 * 30 * DAY,
    adminRecordsSeconds: 547 * DAY,
  },
};

/** A GET /policy that never answers, so the page stays in its loading state. */
const never = () => new Promise<Response>(() => undefined);

async function openPage(path: string, name: string) {
  const app = renderApp(path);
  await screen.findByRole('heading', { level: 1, name }, { timeout: 3000 });
  await settle();
  return app;
}

const region = (name: string) => screen.getByRole('region', { name });

describe('Feature: the text pages state the numbers of GET /policy', () => {
  it('Scenario: Mission says how many candidates and picks the guide uses', async () => {
    mockApi({ 'GET /policy': () => CHANGED });
    await openPage('/mission', 'Our mission');
    const how = region('How the guide answers');
    expect(how).toHaveTextContent('finds up to 40 real candidates');
    expect(how).toHaveTextContent('picks at most 5 of those candidates');
  });

  it('Scenario: Terms states the daily limits, with the signed-in limits when that tier is on', async () => {
    mockApi({ 'GET /policy': () => CHANGED });
    await openPage('/terms', 'Terms of use');
    const service = region('The service');
    expect(service).toHaveTextContent(
      'Tavily web searches: 4 a day for search and 6 for the guide without an account, and 10 a day for search and 12 for the guide when signed in; stored results are free.',
    );
    expect(service).toHaveTextContent('The AI guide: 50 new questions a day per IP address');
    expect(service).toHaveTextContent('Limits reset at 00:00 UTC.');
  });

  it('Scenario: Terms with the registered tier off states one set of web search limits', async () => {
    mockApi();
    await openPage('/terms', 'Terms of use');
    expect(region('The service')).toHaveTextContent(
      'Tavily web searches: 3 a day for search and 3 for the guide; stored results are free. The AI guide: 30 new questions a day',
    );
  });

  it('Scenario: Privacy states every retention period of the API', async () => {
    mockApi({ 'GET /policy': () => CHANGED });
    await openPage('/privacy', 'Privacy');
    const keep = region('How long we keep data');
    expect(keep).toHaveTextContent(
      "Cached searches: a source's results count as fresh for a short time, set per source, and the stored copy is kept up to 2 days. Cached guide answers and their question fingerprints: up to 36 hours",
    );
    expect(keep).toHaveTextContent('Stored results: 10 days.');
    expect(keep).toHaveTextContent(
      'English translations of searches typed in another language: 3 days.',
    );
    expect(keep).toHaveTextContent(
      'Search vectors (the numbers we use to search by meaning): 14 days.',
    );
    expect(keep).toHaveTextContent('or 18 months after you last signed in');
    expect(keep).toHaveTextContent('Reports: 180 days after they were handled.');
    expect(keep).toHaveTextContent('with their GitHub user ID: 18 months.');
    // Not in the API: typed in the page.
    expect(keep).toHaveTextContent('Database backups: 7 days.');
    expect(region('What we process and why')).toHaveTextContent(
      'we keep the stored copy for up to 2 days, so results can still be shown when a source fails. Guide answers are kept for up to 36 hours.',
    );
  });

  it('Scenario: Privacy no longer says searches are cached for 30 minutes (the code keeps them 24 hours)', async () => {
    mockApi();
    await openPage('/privacy', 'Privacy');
    expect(region('How long we keep data')).toHaveTextContent(
      'the stored copy is kept up to 24 hours',
    );
    expect(document.body).not.toHaveTextContent('30 minutes');
  });

  it.each([
    ['/mission', 'Our mission'],
    ['/privacy', 'Privacy'],
    ['/terms', 'Terms of use'],
  ])('Scenario: while GET /policy loads, %s states no number and no note', async (path, name) => {
    mockApi({ 'GET /policy': never });
    const { container } = await openPage(path, name);
    expect(screen.queryByText(/could not be loaded/)).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('{{');
    expect(await axeViolations(container)).toEqual([]);
  });

  it('Scenario: while GET /policy loads, the sentences read without the numbers', async () => {
    mockApi({ 'GET /policy': never });
    await openPage('/terms', 'Terms of use');
    const service = region('The service');
    expect(service).toHaveTextContent(
      'Tavily web searches: a daily limit each for search and the guide; stored results are free. The AI guide: a daily limit of new questions per IP address;',
    );
    expect(service).not.toHaveTextContent(/\b(3|30) /);
  });

  it.each([
    ['/mission', 'Our mission'],
    ['/privacy', 'Privacy'],
    ['/terms', 'Terms of use'],
  ])(
    'Scenario: when GET /policy fails, %s says so once and states no number',
    async (path, name) => {
      mockApi({ 'GET /policy': () => problem(503, 'Service Unavailable') });
      const { container } = await openPage(path, name);
      expect(
        screen.getByText(
          'Some limits and periods could not be loaded, so this page states them without numbers. Reload the page to see them.',
        ),
      ).toBeInTheDocument();
      expect(document.body).not.toHaveTextContent('{{');
      expect(await axeViolations(container)).toEqual([]);
    },
  );

  it('Scenario: when GET /policy fails, Mission and Privacy say it in words', async () => {
    mockApi({ 'GET /policy': () => problem(503, 'Service Unavailable') });
    await openPage('/mission', 'Our mission');
    const how = region('How the guide answers');
    expect(how).toHaveTextContent(
      'A normal search across every source then finds the real candidates.',
    );
    expect(how).toHaveTextContent('A reasoning model picks a few of those candidates');
    expect(how).not.toHaveTextContent(/\b(24|3) /);
  });

  it('Scenario: Privacy without the numbers keeps every period it types and names no period of the API', async () => {
    mockApi({ 'GET /policy': () => problem(503, 'Service Unavailable') });
    await openPage('/privacy', 'Privacy');
    const keep = region('How long we keep data');
    expect(keep).toHaveTextContent('the stored copy is kept for a limited time');
    expect(keep).toHaveTextContent('Stored results: for a limited time.');
    expect(keep).toHaveTextContent('or a set time after you last signed in');
    expect(keep).toHaveTextContent('Reports: a set time after they were handled.');
    expect(keep).toHaveTextContent('with their GitHub user ID: for a set time.');
    expect(keep).not.toHaveTextContent('24 months');
    expect(keep).not.toHaveTextContent('12 months');
    expect(keep).toHaveTextContent('Database backups: 7 days.');
  });

  it('Scenario: the numbers arrive after the page opened', async () => {
    let answer!: (r: Response) => void;
    mockApi({
      'GET /policy': () =>
        new Promise<Response>((resolve) => {
          answer = resolve;
        }),
    });
    await openPage('/terms', 'Terms of use');
    expect(region('The service')).toHaveTextContent('a daily limit of new questions');
    answer(
      new Response(JSON.stringify(POLICY), { headers: { 'content-type': 'application/json' } }),
    );
    await waitFor(() =>
      expect(region('The service')).toHaveTextContent('The AI guide: 30 new questions a day'),
    );
  });

  it('Scenario: the numbers are read once for every text page, and never by Home', async () => {
    const api = mockApi();
    const { router } = renderApp('/');
    await screen.findByRole('list', { name: 'Some of the sources we search' });
    await settle();
    expect(api.callsTo('GET', '/policy')).toHaveLength(0);

    const footer = screen.getByRole('contentinfo');
    await userEvent.click(within(footer).getByRole('link', { name: 'Terms' }));
    await screen.findByRole('heading', { level: 1, name: 'Terms of use' });
    await userEvent.click(within(footer).getByRole('link', { name: 'Privacy' }));
    await screen.findByRole('heading', { level: 1, name: 'Privacy' });
    await settle();
    expect(router.state.location.pathname).toBe('/privacy');
    expect(region('How long we keep data')).toHaveTextContent('24 months after you last signed in');
    expect(api.callsTo('GET', '/policy')).toHaveLength(1);
  });
});
