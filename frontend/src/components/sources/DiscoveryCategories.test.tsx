import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { SourceInfo } from '@/api/types';
import { axeViolations } from '@/test/a11y';
import { ADMIN, SOURCES, STAGING_SOURCES } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderAdmin, renderApp } from '@/test/render';

const scout = STAGING_SOURCES.find((s) => s.id === 'live-web-scout')!;

/** A discovery category as GET /sources lists it (ADR 0051). */
function category(
  id: string,
  name: string,
  family: NonNullable<SourceInfo['family']>,
  lists = true,
): SourceInfo {
  return {
    ...scout,
    id: `discovery-${id}`,
    name,
    shortName: name.slice(0, 12),
    family,
    resourceTypes: lists ? ['ARTICLE', 'PRODUCT'] : ['PRODUCT'],
    lastOutcome: 'SUCCEEDED',
    newThisWeek: 2,
  };
}

const CATEGORIES = [
  category('coding', 'Coding', 'developers'),
  category('health', 'Health', 'everyday-apps', false),
  category('legal', 'Legal', 'business', false),
  category('writing', 'Writing', 'everyday-apps'),
];

describe('Feature: the discovery categories on the Sources page (ADR 0051)', () => {
  it('Scenario: after the other sources, a group of rows per family, in the families’ order', async () => {
    mockApi({ 'GET /sources': () => [...SOURCES, ...CATEGORIES] });
    renderApp('/sources');
    const table = await screen.findByRole('table', { name: 'Every source' });
    const groups = within(table)
      .getAllByRole('rowheader')
      .filter((h) => h.getAttribute('scope') === 'rowgroup')
      .map((h) => h.textContent);
    expect(groups).toEqual([
      'Discovery · Everyday apps',
      'Discovery · Business',
      'Discovery · Developers',
    ]);
    // The other sources first, then one group per family.
    expect(table.querySelectorAll('tbody')).toHaveLength(4);
    const developers = table.querySelectorAll('tbody')[3]!;
    expect(within(developers).getByRole('link', { name: /^Coding/ })).toBeInTheDocument();
  });

  it('Scenario: the page says honestly what is reviewed and what is not', async () => {
    mockApi({ 'GET /sources': () => [...SOURCES, ...CATEGORIES] });
    renderApp('/sources');
    const note = await screen.findByText(/List articles are added without review\./);
    expect(note).toHaveTextContent(
      'Products and posts about them are added only after a person checks them.',
    );
    // Health adds products only; coding adds list articles too.
    const health = screen.getByRole('link', { name: /^Health/ }).closest('tr')!;
    expect(health).not.toHaveTextContent('Article');
    const coding = screen.getByRole('link', { name: /^Coding/ }).closest('tr')!;
    expect(coding).toHaveTextContent('Article');
  });

  it('Scenario: health and legal carry their notice', async () => {
    mockApi({ 'GET /sources': () => [...SOURCES, ...CATEGORIES] });
    renderApp('/sources');
    const health = (await screen.findByRole('link', { name: /^Health/ })).closest('th')!;
    expect(health).toHaveTextContent(
      'Not medical advice. We do not check whether a tool is approved as a medical device.',
    );
    expect(screen.getByRole('link', { name: /^Legal/ }).closest('th')!).toHaveTextContent(
      'Not legal advice. We do not check whether a tool is right for your country or case.',
    );
    expect(screen.getByRole('link', { name: /^Coding/ }).closest('th')!).not.toHaveTextContent(
      'advice',
    );
  });

  it('Scenario: without discovery categories there are no family groups and no discovery note', async () => {
    mockApi({ 'GET /sources': () => SOURCES });
    renderApp('/sources');
    const table = await screen.findByRole('table', { name: 'Every source' });
    expect(table.querySelectorAll('tbody')).toHaveLength(1);
    expect(screen.queryByText(/List articles are added without review/)).not.toBeInTheDocument();
  });

  it('Scenario: the table with its family groups passes axe', async () => {
    mockApi({ 'GET /sources': () => [...SOURCES, ...CATEGORIES] });
    const { container } = renderApp('/sources');
    await screen.findByRole('link', { name: /^Coding/ });
    expect(await axeViolations(container)).toEqual([]);
  });
});

describe('Feature: discovery categories in the back office (ADR 0051)', () => {
  it('Scenario: the review queue filters by category and names every collector that found a page', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /sources': () => [...SOURCES, ...CATEGORIES],
      'GET /admin/review-items': () => ({
        items: [
          {
            id: '66666666-6666-6666-8666-666666666666',
            kind: 'RESOURCE_CANDIDATE',
            status: 'OPEN',
            reason: 'New PRODUCT (health) found by discovery-health: Scribe',
            details: JSON.stringify({
              version: 2,
              sourceId: 'discovery-health',
              url: 'https://scribe.example/',
              title: 'Scribe',
              type: 'PRODUCT',
              tags: ['health', 'writing'],
              foundBy: [{ sourceId: 'discovery-health' }, { sourceId: 'discovery-writing' }],
            }),
            categories: ['health', 'writing'],
            createdAt: '2026-11-05T03:20:00Z',
          },
        ],
        total: 1,
      }),
    });
    renderAdmin('/admin/review?kind=RESOURCE_CANDIDATE&category=health');
    const link = await screen.findByRole('link', { name: 'Scribe' });
    const card = link.closest('dl') as HTMLElement;
    expect(card).toHaveTextContent('discovery-health, discovery-writing');
    expect(card).toHaveTextContent('Not medical advice.');
    expect(m.callsTo('GET', '/admin/review-items').at(-1)?.search).toContain('category=health');
    expect(await screen.findByRole('combobox', { name: 'Category' })).toHaveValue('health');
  });

  it('Scenario: a collector that never ran has its Run now too', async () => {
    const m = mockApi({
      'GET /me': () => ADMIN,
      'GET /sources': () => [...SOURCES, ...CATEGORIES],
      'GET /admin/scout-runs': () => [],
      'POST /admin/scout-runs': () => [],
    });
    renderAdmin('/admin/runs');
    const run = await screen.findByRole('button', { name: 'Run discovery-coding now' });
    await userEvent.click(run);
    await vi.waitFor(() =>
      expect(m.callsTo('POST', '/admin/scout-runs').at(-1)?.body).toEqual({
        sourceId: 'discovery-coding',
      }),
    );
  });
});
