import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axeViolations } from '@/test/a11y';
import {
  ADMIN,
  ANON,
  HEALTH_SOURCES,
  STAGING_SOURCES,
  USER,
  makeCard,
  makeDetail,
  makeRecent,
  makeReviewEvidence,
  makeSearch,
  makeStatus,
} from '@/test/fixtures';
import { mockViewport } from '@/test/viewport';
import { mockApi, sse } from '@/test/mockApi';
import { renderAdmin, renderApp, settle } from '@/test/render';
import { clearApiCache } from '@/hooks/useApi';

const ID = '11111111-1111-5111-8111-111111111111';

describe('a11y smoke (axe, color-contrast excluded: not computable in jsdom)', () => {
  it('the helper really detects violations', async () => {
    const div = document.createElement('div');
    div.innerHTML = '<img src="x.png"><button></button>';
    document.body.appendChild(div);
    expect((await axeViolations(div)).length).toBeGreaterThan(0);
    div.remove();
  });

  it('home', async () => {
    mockApi();
    const { container } = renderApp('/');
    await screen.findByRole('banner');
    await screen.findByRole('list', { name: 'Some of the sources we search' });
    await settle();
    expect(await axeViolations(container)).toEqual([]);
  });

  describe('home, with the lists loaded', () => {
    const lists = {
      'GET /sources': () => STAGING_SOURCES,
      'GET /recent': () => makeRecent(),
      'GET /events/upcoming': () => ({
        generatedAt: '2026-11-05T09:00:00Z',
        items: [
          makeCard({
            id: '22222222-2222-5222-8222-222222222222',
            title: 'AI Meetup',
            type: 'EVENT',
            location: 'Zurich',
            eventKind: 'MEETUP',
            timeline: {
              ...makeCard().timeline,
              eventStart: {
                value: '2026-11-07T00:00:00Z',
                precision: 'DAY',
                status: 'KNOWN',
                sourceId: 'x',
              },
              eventEnd: null,
            },
          }),
        ],
      }),
    };
    afterEach(() => {
      delete document.documentElement.dataset.theme;
    });

    for (const theme of ['light', 'dark'] as const) {
      it(`wide screen, ${theme} theme: the two tables`, async () => {
        document.documentElement.dataset.theme = theme;
        mockApi(lists);
        const { container } = renderApp('/');
        await screen.findByRole('link', { name: 'AI Meetup' });
        await screen.findByRole('list', { name: 'Some of the sources we search' });
        await settle();
        expect(await axeViolations(container)).toEqual([]);
      });

      it(`phone, ${theme} theme: the tabs and the open menu`, async () => {
        document.documentElement.dataset.theme = theme;
        mockViewport(390);
        mockApi(lists);
        const { container } = renderApp('/');
        await screen.findByRole('link', { name: 'Fresh MCP server' });
        await screen.findByRole('list', { name: 'Some of the sources we search' });
        await userEvent.click(screen.getByRole('button', { name: 'Menu' }));
        await settle();
        expect(await axeViolations(container)).toEqual([]);
      });
    }
  });

  it('search results with the sources grid and the partial line, opened', async () => {
    mockApi({
      'GET /search': () =>
        makeSearch({
          sources: [
            makeStatus(),
            makeStatus({ sourceId: 'x', name: 'X', outcome: 'TIMED_OUT', servedFrom: 'NONE' }),
          ],
          coverage: {
            partial: true,
            sourcesConfigured: 2,
            sourcesQueried: 2,
            sourcesSucceeded: 1,
            note: 'n',
          },
        }),
    });
    const { container } = renderApp('/search?q=translate&type=MODEL');
    await screen.findByRole('list', { name: 'Sources searched' });
    await userEvent.click(screen.getByRole('button', { name: 'Which?' }));
    expect(await axeViolations(container)).toEqual([]);
  });

  describe('results and the resource page', () => {
    const picked = makeCard({ id: '33333333-3333-5333-8333-333333333333', title: 'Picked One' });
    const answers = {
      'GET /search': () => makeSearch({ results: [makeCard(), picked], total: 2 }),
      'POST /guide/stream': () =>
        sse({
          status: 'OK',
          searchQuery: 'translate',
          candidatesConsidered: 2,
          rejected: 0,
          sources: [],
          notices: [],
          generatedAt: '2026-11-05T10:00:00Z',
          model: { provider: 'nebius-token-factory', fastModel: 'f', reasoningModel: 'r' },
          recommendations: [
            {
              resourceId: picked.id,
              title: picked.title,
              url: picked.url,
              type: picked.type,
              reason: 'It translates.',
              quote: 'Translates documents',
              sourceIds: ['github'],
              aiReasoned: true,
              reasonCheck: 'NOT_CHECKED',
            },
          ],
        }),
      [`GET /resources/${ID}`]: () => makeDetail(),
    };
    afterEach(() => {
      delete document.documentElement.dataset.theme;
    });

    for (const theme of ['light', 'dark'] as const) {
      it(`wide screen, ${theme} theme: the picks, the list and the filters`, async () => {
        document.documentElement.dataset.theme = theme;
        mockApi(answers);
        const { container } = renderApp(
          '/search?q=translate+my+docs+free&ask=translate+my+docs+free',
        );
        await screen.findByRole('link', { name: '#1 Picked One' });
        await userEvent.click(screen.getByRole('button', { name: 'Filters' }));
        await settle();
        expect(await axeViolations(container)).toEqual([]);
      });

      it(`phone, ${theme} theme: the list and the filters drawer`, async () => {
        document.documentElement.dataset.theme = theme;
        mockViewport(390);
        mockApi(answers);
        const { container } = renderApp('/search?q=translate');
        await screen.findByRole('link', { name: 'Picked One' });
        await userEvent.click(screen.getByRole('button', { name: 'Filters' }));
        await screen.findByRole('dialog', { name: 'Filters' });
        await settle();
        expect(await axeViolations(container)).toEqual([]);
      });

      it(`${theme} theme: the resource page`, async () => {
        document.documentElement.dataset.theme = theme;
        mockApi(answers);
        const { container } = renderApp(`/resource/${ID}`);
        await screen.findByRole('heading', { level: 1 });
        await settle();
        expect(await axeViolations(container)).toEqual([]);
      });
    }
  });

  it('search empty and error states', async () => {
    mockApi({ 'GET /search': () => makeSearch({ results: [], total: 0 }) });
    const a = renderApp('/search?q=zzz');
    await screen.findByText('No results from the sources searched');
    await settle();
    expect(await axeViolations(a.container)).toEqual([]);
    a.unmount();
    clearApiCache();
    mockApi({
      'GET /search': () =>
        new Response('{}', { status: 500, headers: { 'content-type': 'application/json' } }),
    });
    const b = renderApp('/search?q=zzz');
    await screen.findByRole('alert');
    await settle();
    expect(await axeViolations(b.container)).toEqual([]);
  });

  it('resource detail', async () => {
    mockApi({
      [`GET /resources/${ID}`]: () =>
        makeDetail({
          relationships: [
            {
              kind: 'USES',
              targetTitle: 'X',
              targetId: null,
              verification: 'UNVERIFIED',
              evidence: [],
            },
          ],
          assertions: [
            {
              field: 'license',
              value: 'MIT',
              origin: 'CURATED',
              verification: 'VERIFIED',
              evidence: [],
            },
          ],
        }),
    });
    const { container } = renderApp(`/resource/${ID}`);
    await screen.findByRole('heading', { name: 'Report wrong or outdated info' });
    await settle();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('sources, admin', async () => {
    mockApi({
      'GET /me': () => ADMIN,
      'GET /admin/review-items': () => ({
        items: [
          {
            id: '66666666-6666-5666-8666-666666666666',
            kind: 'USER_REPORT',
            status: 'OPEN',
            reason: 'Wrong price',
            details: 'It is free',
            resourceId: ID,
            resourceTitle: 'Acme',
            createdAt: '2026-10-21T10:00:00Z',
          },
        ],
        total: 1,
      }),
      'GET /admin/review-items/*': () =>
        makeReviewEvidence({
          id: '66666666-6666-5666-8666-666666666666',
          kind: 'ENRICHMENT_VALIDATION',
          status: 'OPEN',
          reason: 'Wrong price',
          createdAt: '2026-10-21T10:00:00Z',
        }),
      'GET /admin/scout-runs': () => [
        {
          id: '77777777-7777-5777-8777-777777777777',
          sourceId: 'github',
          status: 'FAILED',
          startedAt: '2026-10-21T10:00:00Z',
          finishedAt: null,
          itemsSeen: 0,
          itemsChanged: 0,
          message: 'rate limited',
        },
      ],
      'GET /admin/overview': () => ({
        generatedAt: '2026-11-04T09:00:00Z',
        openReviewItems: [{ kind: 'USER_REPORT', count: 1 }],
        enrichmentBacklog: 3,
        budgets: [{ name: 'guide', dailyLimit: 200, usedToday: 10 }],
        webCredits: null,
        recentRuns: [
          {
            id: '77777777-7777-5777-8777-777777777777',
            sourceId: 'github',
            status: 'FAILED',
            startedAt: '2026-10-21T10:00:00Z',
            finishedAt: null,
            itemsSeen: 0,
            itemsChanged: 0,
            message: 'rate limited',
          },
        ],
        failedRunsLast24h: 1,
        homeExamples: {
          shown: [
            { path: 'USE_AI', count: 1 },
            { path: 'BUILD_WITH_AI', count: 2 },
          ],
          hidden: [
            { outcome: 'PENDING', count: 1 },
            { outcome: 'WEAK', count: 0 },
            { outcome: 'FAIL', count: 1 },
          ],
          paused: 0,
        },
        overridesActive: 1,
        grafanaUrl: null,
      }),
      'GET /admin/home-examples': () => [
        {
          id: '22222222-2222-5222-8222-222222222222',
          label: 'Translate documents',
          query: 'translate documents',
          path: 'USE_AI',
          status: 'ACTIVE',
          origin: 'BUILT_IN',
          model: null,
          expected: [{ id: '11111111-1111-5111-8111-111111111111', title: 'DeepL Translator' }],
          check: {
            outcome: 'PASS',
            bestRank: 2,
            resultCount: 40,
            checkedAt: '2026-11-05T02:41:00Z',
          },
          version: 0,
          updatedAt: '2026-11-04T10:00:00Z',
        },
      ],
      'GET /admin/enrichment-queue': () => ({
        items: [
          {
            resourceId: '11111111-1111-5111-8111-111111111111',
            title: 'Acme MCP',
            state: 'WAITING',
            waiting: 'RETRY',
            firstDiscoveredAt: '2026-11-04T09:00:00Z',
            failedRuns: 1,
            lastOutcome: 'FAILED',
            lastRunAt: '2026-11-05T08:00:00Z',
          },
          {
            resourceId: '22222222-2222-5222-8222-222222222222',
            title: 'Top MCP',
            state: 'PRIORITISED',
            waiting: 'NEW',
            firstDiscoveredAt: '2026-11-04T08:00:00Z',
            failedRuns: 0,
            lastOutcome: null,
            lastRunAt: null,
          },
        ],
        next: 'c2',
      }),
      'GET /admin/audit': () => ({
        items: [
          {
            id: '99999999-9999-5999-8999-999999999999',
            adminId: '12345',
            action: 'RESOLVE_REVIEW_ITEM',
            targetId: null,
            at: '2026-11-01T09:00:00Z',
            outcome: 'ACCEPTED',
          },
        ],
        total: 1,
      }),
      'GET /me/bookmarks': () => [],
      'GET /sources': () => HEALTH_SOURCES,
    });
    const s = renderApp('/sources');
    await screen.findByRole('heading', { level: 2, name: 'Every source' });
    await settle();
    expect(await axeViolations(s.container)).toEqual([]);
    s.unmount();
    const ov = renderAdmin('/admin');
    await within(await screen.findByRole('main')).findByText('Failed');
    await settle();
    expect(await axeViolations(ov.container)).toEqual([]);
    ov.unmount();
    const review = renderAdmin('/admin/review');
    await within(await screen.findByRole('main')).findByText('Wrong price', { selector: 'button' });
    await screen.findByText('Free for everyone.');
    expect(await axeViolations(review.container)).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: 'Keys (?)' }));
    expect(await axeViolations(review.container)).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: 'Close' }));
    await userEvent.click(screen.getByRole('checkbox', { name: 'Pick: Wrong price' }));
    expect(await axeViolations(review.container)).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: 'Reject' }));
    await screen.findByRole('dialog', { name: 'Reject 1 item?' });
    expect(await axeViolations(review.container)).toEqual([]);
    review.unmount();
    const runs = renderAdmin('/admin/runs');
    await within(await screen.findByRole('main')).findByText('Failed');
    expect(await axeViolations(runs.container)).toEqual([]);
    runs.unmount();
    const examples = renderAdmin('/admin/examples');
    await within(await screen.findByRole('main')).findByText('Shown · expected result at #2');
    expect(await axeViolations(examples.container)).toEqual([]);
    examples.unmount();
    const enrichment = renderAdmin('/admin/enrichment');
    await within(await screen.findByRole('main')).findByRole('link', { name: 'Acme MCP' });
    expect(await axeViolations(enrichment.container)).toEqual([]);
    await userEvent.click(screen.getByRole('button', { name: 'Skip Acme MCP' }));
    await screen.findByRole('textbox', { name: 'Reason (optional)' });
    expect(await axeViolations(enrichment.container)).toEqual([]);
    enrichment.unmount();
    const audit = renderAdmin('/admin/audit');
    await within(await screen.findByRole('main')).findByText('Review decision');
    expect(await axeViolations(audit.container)).toEqual([]);
  });

  describe('list pages with the shared controls: Saved, Sources, the review queue', () => {
    afterEach(() => {
      delete document.documentElement.dataset.theme;
    });
    const api = {
      'GET /me/bookmarks': () => [
        {
          resourceId: ID,
          title: 'Postgres MCP',
          url: 'https://github.com/a/pg',
          type: 'MCP_SERVER',
          savedAt: '2026-10-20T10:00:00Z',
        },
        {
          resourceId: '22222222-2222-5222-8222-222222222222',
          title: 'pgvector',
          url: 'https://github.com/pgvector/pgvector',
          type: 'LIBRARY',
          savedAt: '2026-10-21T10:00:00Z',
        },
      ],
      'GET /sources': () => HEALTH_SOURCES,
      'GET /admin/review-items/*': () =>
        makeReviewEvidence({
          id: '66666666-6666-5666-8666-666666666666',
          kind: 'ENRICHMENT_VALIDATION',
          status: 'OPEN',
          reason: 'Wrong price',
          createdAt: '2026-10-21T10:00:00Z',
        }),
      'GET /admin/review-items': () => ({
        items: [
          {
            id: '66666666-6666-5666-8666-666666666666',
            kind: 'USER_REPORT',
            status: 'OPEN',
            reason: 'Wrong price',
            details: 'It is free',
            resourceId: ID,
            resourceTitle: 'Acme',
            createdAt: '2026-10-21T10:00:00Z',
          },
        ],
        total: 1,
      }),
    };
    const pages: Array<{ path: string; me: () => unknown; ready: string; row: string }> = [
      { path: '/saved', me: () => USER, ready: 'Saved resources', row: 'Postgres MCP' },
      { path: '/sources', me: () => ANON, ready: 'Every source', row: 'All' },
      { path: '/admin/review', me: () => ADMIN, ready: 'Review queue', row: 'Wrong price' },
    ];
    for (const theme of ['light', 'dark'] as const) {
      for (const width of [1280, 390]) {
        it(`${theme} theme, ${width} px wide`, async () => {
          document.documentElement.dataset.theme = theme;
          mockViewport(width);
          for (const { path, me, ready, row } of pages) {
            mockApi({ ...api, 'GET /me': me });
            const view = path.startsWith('/admin') ? renderAdmin(path) : renderApp(path);
            await screen.findByRole('heading', { name: ready });
            await within(await screen.findByRole('main')).findAllByText(row);
            await settle();
            expect(await axeViolations(view.container)).toEqual([]);
            view.unmount();
          }
        });
      }
    }
  });

  describe('text pages: Mission, Privacy, Terms, Not found, Sign in, Account', () => {
    afterEach(() => {
      delete document.documentElement.dataset.theme;
    });
    const pages: Array<{ path: string; me: () => unknown; ready: string }> = [
      { path: '/mission', me: () => ANON, ready: 'Our mission' },
      { path: '/privacy', me: () => ANON, ready: 'Privacy' },
      { path: '/terms', me: () => ANON, ready: 'Terms of use' },
      { path: '/no/such/page', me: () => ANON, ready: 'Page not found' },
      { path: '/signin', me: () => ANON, ready: 'Choose how to sign in' },
      { path: '/account', me: () => USER, ready: 'Delete your account' },
    ];
    for (const theme of ['light', 'dark'] as const) {
      for (const width of [1280, 390]) {
        it(`${theme} theme, ${width} px wide`, async () => {
          document.documentElement.dataset.theme = theme;
          mockViewport(width);
          for (const { path, me, ready } of pages) {
            mockApi({ 'GET /me': me });
            const view = path.startsWith('/admin') ? renderAdmin(path) : renderApp(path);
            await screen.findByRole('heading', { name: ready });
            await settle();
            expect(await axeViolations(view.container)).toEqual([]);
            view.unmount();
          }
        });
      }
    }
  });

  it('account, with the delete confirmation open', async () => {
    mockApi({ 'GET /me': () => USER });
    const { container } = renderApp('/account');
    await userEvent.click(await screen.findByRole('button', { name: 'Delete my account' }));
    await screen.findByRole('alertdialog');
    expect(await axeViolations(container)).toEqual([]);
  });
});
