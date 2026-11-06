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
    freeAnswers: 45,
    webSearches: { guest: { search: 4, guide: 6 }, registered: { search: 10, guide: 12 } },
  },
  retention: {
    webSearchCountersSeconds: 2 * DAY,
    guideQuestionCountersSeconds: 5 * DAY,
    webNewsSeconds: 200 * DAY,
    pendingReviewPagesSeconds: 150 * DAY,
    listIndexSeconds: 45 * DAY,
    pageTextSeconds: 48 * 3600,
    pageReadSeconds: 60 * DAY,
    backupsSeconds: 9 * DAY,
    cachedSearchesSeconds: 2 * DAY,
    cachedGuideAnswersSeconds: 36 * 3600,
    translationsSeconds: 3 * DAY,
    queryVectorsSeconds: 14 * DAY,
    storedWebResultsSeconds: 10 * DAY,
    inactiveAccountsSeconds: 547 * DAY,
    handledReportsSeconds: 6 * 30 * DAY,
    adminRecordsSeconds: 547 * DAY,
    runtimeSettingChangesSeconds: 365 * DAY,
  },
  sources: {
    categories: 41,
    categorySearches: 47,
    quoteChars: 350,
    articleExcerptChars: 450,
    productPageExcerptChars: 2600,
    productPages: 6,
    errorLogChars: 250,
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
    expect(keep).toHaveTextContent('the reason or note they wrote: 18 months.');
    expect(keep).toHaveTextContent(
      'the reason they wrote: 12 months. The latest change of each limit stays',
    );
    expect(keep).toHaveTextContent(
      'Web search counters: 2 days. Rate-limit counters: 1 minute or 1 hour (the length of the limit). Guide questions per day: 5 days.',
    );
    expect(keep).toHaveTextContent(
      'Database backups: 9 days. Anything deleted above is gone from the backups within 9 days.',
    );
    expect(keep).toHaveTextContent('Their cleaned text: up to 2 days, to try again.');
    expect(keep).toHaveTextContent('a fingerprint of its text: 60 days.');
    expect(keep).toHaveTextContent('200 days after we last retrieved them');
    expect(keep).toHaveTextContent('until we decide, then 150 days.');
    expect(keep).toHaveTextContent('45 days after a list was last read');
    // Not in the API: typed in the page.
    expect(keep).toHaveTextContent('Technical logs and request traces: up to 3 months.');
    expect(region('What we process and why')).toHaveTextContent(
      'we keep the stored copy for up to 2 days, so results can still be shown when a source fails. Guide answers are kept for up to 36 hours.',
    );
  });

  it('Scenario: Privacy states what the sources read, as the code has it', async () => {
    mockApi({ 'GET /policy': () => CHANGED });
    await openPage('/privacy', 'Privacy');
    const sources = region('Public data about AI resources');
    expect(sources).toHaveTextContent('41 categories of AI tools');
    expect(sources).toHaveTextContent('47 searches in all');
    expect(sources).toHaveTextContent('short quotes of up to 350 characters');
    expect(sources).toHaveTextContent(
      'up to 450 characters for an article, up to 2,600 characters for a product page',
    );
    expect(sources).toHaveTextContent('read up to 6 pages of the product');
    expect(sources).toHaveTextContent('up to 2 days in our cache');
    expect(sources).toHaveTextContent('cut to 250 characters');
  });

  it('Scenario: Terms states how many free answers a day the guide gives', async () => {
    mockApi({ 'GET /policy': () => CHANGED });
    await openPage('/terms', 'Terms of use');
    expect(region('The service')).toHaveTextContent('does not count either (up to 45 a day)');
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
    expect(keep).toHaveTextContent('the reason or note they wrote: for a set time.');
    expect(keep).toHaveTextContent('the reason they wrote: for a set time. The latest change');
    expect(keep).not.toHaveTextContent('24 months');
    expect(keep).not.toHaveTextContent('12 months');
    expect(keep).toHaveTextContent('Database backups: a limited number of days.');
    expect(keep).not.toHaveTextContent('7 days');
    expect(keep).toHaveTextContent('Technical logs and request traces: up to 3 months.');
    const sources = region('Public data about AI resources');
    expect(sources).toHaveTextContent('a fixed set of categories');
    expect(sources).not.toHaveTextContent('300 characters');
    expect(sources).not.toHaveTextContent('{{');
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
