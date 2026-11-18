import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axeViolations } from '@/test/a11y';
import { ADMIN, USER, makeCard, makeDetail, makeSearch } from '@/test/fixtures';
import { mockApi, type Handler } from '@/test/mockApi';
import { renderAdmin, renderApp } from '@/test/render';

const never: Handler = () => new Promise<Response>(() => undefined);
const ID = '11111111-1111-5111-8111-111111111111';
const RUN = '77777777-7777-5777-8777-777777777777';
const ITEM = {
  id: '66666666-6666-5666-8666-666666666661',
  kind: 'ENRICHMENT_VALIDATION' as const,
  status: 'OPEN' as const,
  reason: 'Item 1',
  details: null,
  resourceId: null,
  resourceTitle: 'Acme 1',
  createdAt: '2026-11-01T10:00:00Z',
};
const MCP = makeCard({ id: ID, title: 'Postgres MCP', type: 'MCP_SERVER' });

interface Case {
  page: string;
  open: () => void | Promise<void>;
  handlers: Record<string, Handler>;
  label: string;
  where?: string;
  onePageStatus?: boolean;
}

const publicPages: Case[] = [
  {
    page: 'search results',
    open: () => void renderApp('/search?q=translate'),
    handlers: { 'GET /search': never },
    label: 'Searching the listed sources…',
    where: '.h2-results__skeleton',
  },
  {
    page: 'resource detail',
    open: () => void renderApp(`/resource/${ID}`),
    handlers: { [`GET /resources/${ID}`]: never },
    label: 'Loading…',
    where: '.h2-res__loading',
  },
  {
    page: 'home: Recent and Upcoming events',
    open: () => void renderApp('/'),
    handlers: { 'GET /recent': never, 'GET /events/upcoming': never },
    label: 'Loading…',
  },
  {
    page: 'Events',
    open: () => void renderApp('/events'),
    handlers: { 'GET /events/upcoming': never },
    label: 'Loading…',
  },
  {
    page: 'News',
    open: () => void renderApp('/news'),
    handlers: { 'GET /recent': never },
    label: 'Loading…',
  },
  {
    page: 'Sources',
    open: () => void renderApp('/sources'),
    handlers: { 'GET /sources': never },
    label: 'Loading…',
    where: '.h2-sources__all',
  },
  {
    page: 'Saved',
    open: () => void renderApp('/saved'),
    handlers: { 'GET /me': () => USER, 'GET /me/bookmarks': never },
    label: 'Loading…',
  },
  {
    page: 'Account',
    open: () => void renderApp('/account'),
    handlers: { 'GET /me': never },
    label: 'Loading…',
  },
  {
    page: 'Sign in',
    open: () => void renderApp('/signin'),
    handlers: { 'GET /me': never },
    label: 'Loading…',
  },
  {
    page: 'the guide',
    open: () => void renderApp('/search?q=translate&ask=translate+documents+for+free'),
    handlers: { 'GET /search': () => makeSearch(), 'POST /guide/stream': never },
    label: 'Guide is reading the results… picks will appear here',
    where: '.h2-picks',
  },
  {
    page: 'the setup guide',
    open: async () => {
      renderApp(`/resource/${ID}`);
      await userEvent.click(await screen.findByRole('button', { name: 'Set it up' }));
    },
    handlers: {
      [`GET /resources/${ID}`]: () => makeDetail({ card: MCP }),
      [`GET /resources/${ID}/setup`]: never,
      [`POST /resources/${ID}/guide`]: never,
    },
    label: 'Reading its documentation… (up to half a minute)',
    where: '.setup-part [aria-live]',
  },
];

const adminPages: Case[] = [
  {
    page: 'back office, before it knows who is signed in',
    open: () => void renderAdmin('/admin'),
    handlers: { 'GET /me': never },
    label: 'Loading…',
  },
  {
    page: 'Overview',
    open: () => void renderAdmin('/admin'),
    handlers: { 'GET /admin/overview': never },
    label: 'Loading…',
  },
  {
    page: 'review queue',
    open: () => void renderAdmin('/admin/review'),
    handlers: { 'GET /admin/review-items': never },
    label: 'Loading…',
  },
  {
    page: 'candidate detail: the evidence',
    open: () => void renderAdmin('/admin/review'),
    handlers: {
      'GET /admin/review-items': () => ({ items: [ITEM], total: 1 }),
      [`GET /admin/review-items/${ITEM.id}/evidence`]: never,
    },
    label: 'Loading the evidence…',
    where: '.h2-admin__evidence',
  },
  {
    page: 'research trace',
    open: () => void renderAdmin(`/admin/review/research/${RUN}`),
    handlers: { [`GET /admin/review-research/${RUN}`]: never },
    label: 'Loading the research…',
  },
  {
    page: 'Scout runs',
    open: () => void renderAdmin('/admin/runs'),
    handlers: { 'GET /admin/scout-runs': never },
    label: 'Loading…',
  },
  {
    page: 'enrichment queue',
    open: () => void renderAdmin('/admin/enrichment'),
    handlers: { 'GET /admin/enrichment-queue': never },
    label: 'Loading…',
  },
  {
    page: 'home examples',
    open: () => void renderAdmin('/admin/examples'),
    handlers: { 'GET /admin/home-examples': never },
    label: 'Loading…',
  },
  {
    page: 'audit log',
    open: () => void renderAdmin('/admin/audit'),
    handlers: { 'GET /admin/audit': never },
    label: 'Loading…',
  },
  {
    page: 'Settings, with shadow libraries and the other panels',
    open: () => void renderAdmin('/admin/settings'),
    handlers: {
      'GET /admin/config': never,
      'GET /admin/runtime-settings': never,
      'GET /admin/discovery-hosts': never,
      'GET /admin/shadow-libraries': never,
      'GET /admin/known-targets': never,
    },
    label: 'Loading…',
    onePageStatus: true,
  },
];

function findSkeleton(where: string | undefined): Element | null {
  const scope = where ? document.querySelector(where) : document.body;
  return scope?.matches('.h2-skel') ? scope : (scope?.querySelector('.h2-skel') ?? null);
}

async function skeletonIn(where: string | undefined): Promise<Element> {
  await act(async () => {
    for (let i = 0; i < 200 && !findSkeleton(where); i++)
      await new Promise((resolve) => setTimeout(resolve, 25));
    await new Promise((resolve) => setTimeout(resolve, 50));
  });
  const bones = findSkeleton(where);
  expect(bones).toBeTruthy();
  return bones!;
}

function announced(bones: Element, label: string) {
  const live = bones.parentElement?.closest('[role="status"], [aria-live]');
  const status = bones.parentElement?.querySelector(':scope > [role="status"]');
  return [live, status].filter((e) => e?.textContent?.includes(label));
}

describe('Feature: a skeleton in the shape of the content while a page loads', () => {
  it.each([
    ...publicPages.map((c) => ({ side: 'public' as const, ...c })),
    ...adminPages.map((c) => ({ side: 'admin' as const, ...c })),
  ])(
    'Scenario: $side, $page: the skeleton is hidden from screen readers, one status says it loads, no axe violation',
    async ({ side, ...c }) => {
      mockApi({ ...(side === 'admin' ? { 'GET /me': () => ADMIN } : {}), ...c.handlers });
      await c.open();
      if (side === 'admin' && c.handlers['GET /me'] === undefined)
        await screen.findByRole('heading', { level: 1 }, { timeout: 5000 });
      const bones = await skeletonIn(c.where);
      expect(bones).toHaveAttribute('aria-hidden', 'true');
      expect(bones.closest('[aria-hidden="true"]')).toBe(bones);
      expect(bones.textContent).toBe('');
      if (c.onePageStatus)
        expect(
          screen.getAllByRole('status').filter((e) => e.textContent?.includes(c.label)),
        ).toHaveLength(1);
      else expect(announced(bones, c.label)).toHaveLength(1);
      expect(await axeViolations(document.body)).toEqual([]);
    },
    15_000,
  );
});

describe('Feature: the skeleton waits before it shows and does not move for reduced motion', () => {
  const css = readFileSync(join(process.cwd(), 'src/ui/skeleton.css'), 'utf8');
  const tokens = readFileSync(join(process.cwd(), 'src/styles/tokens.css'), 'utf8');
  const rule = (selector: string, text = css) =>
    new RegExp(`(^|\\n)${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')} \\{([^}]*)\\}`).exec(
      text,
    )?.[2] ?? '';

  it('Scenario: it stays invisible for the skeleton delay, so a fast load does not flash', () => {
    expect(tokens).toMatch(/--skeleton-delay: 180ms;/);
    expect(rule('.h2-skel')).toMatch(/opacity: 0;/);
    expect(rule('.h2-skel')).toMatch(
      /animation: h2-skel-show 1ms linear var\(--skeleton-delay\) forwards;/,
    );
  });

  it('Scenario: it keeps its place from the first frame, so the layout does not jump', () => {
    expect(rule('.h2-skel')).not.toMatch(/display: none|visibility/);
    expect(rule('.h2-skel__bar')).toMatch(/height: 1lh;/);
  });

  it('Scenario: under prefers-reduced-motion the bars do not pulse, and the skeleton still shows', () => {
    const reduced = /@media \(prefers-reduced-motion: reduce\) \{([\s\S]*?)\n\}/.exec(css)?.[1];
    expect(reduced).toMatch(/\.h2-skel__bar \{\s*animation: none;/);
    expect(reduced).not.toMatch(/\.h2-skel \{/);
  });

  it('Scenario: no style sheet turns off the wait for reduced motion, so the skeleton still shows', () => {
    const sheets = readdirSync(join(process.cwd(), 'src'), { withFileTypes: true, recursive: true })
      .filter((e) => e.isFile() && e.name.endsWith('.css'))
      .map((e) => readFileSync(join(e.parentPath, e.name), 'utf8'));
    const everything = sheets.flatMap((text) =>
      [...text.matchAll(/@media \(prefers-reduced-motion: reduce\) \{([\s\S]*?)\n\}/g)].flatMap(
        (m) =>
          [...m[1]!.matchAll(/([^{}]+)\{[^}]*animation: none/g)]
            .map((r) => r[1]!)
            .filter(
              (sel) => /(^|,)\s*[.\w-]*\s\*\s*(,|$)/.test(sel) || /\.h2-skel(?![_\w-])/.test(sel),
            ),
      ),
    );
    expect(everything.filter((sel) => !sel.includes(':not(.h2-skel)'))).toEqual([]);
  });

  it('Scenario: the colours are tokens, so it works in the light and the dark theme', () => {
    expect(css).not.toMatch(/#[0-9a-f]{3,8}\b|rgba?\(/i);
    expect(rule('.h2-skel__bar::before')).toMatch(/background: var\(--surface-tint\);/);
    expect(rule('.h2-skel.is-on-term .h2-skel__bar::before')).toMatch(
      /background: var\(--term-line\);/,
    );
  });
});
