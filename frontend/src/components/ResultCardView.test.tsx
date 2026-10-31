import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { BookmarksProvider } from '@/context/BookmarksContext';
import { makeCard, known, unknownDate } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { BookmarkButton } from './BookmarkButton';
import { ResultCardView } from './ResultCardView';
import type { ResultCard } from '@/api/types';
import { settle } from '@/test/render';

function view(card: ResultCard, detailed = false) {
  mockApi();
  return render(
    <MemoryRouter>
      <BookmarksProvider><ResultCardView card={card} detailed={detailed} /></BookmarksProvider>
    </MemoryRouter>,
  );
}

describe('ResultCardView', () => {
  it('external link has safe attributes and shows hostname', async () => {
    view(makeCard());
    const link = screen.getByRole('link', { name: /Open original/ });
    expect(link).toHaveAttribute('href', 'https://www.acme.example/translator');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer nofollow');
    expect(link).toHaveTextContent('acme.example');
    await settle();
  });

  it('refuses to link non-http(s) urls', async () => {
    view(makeCard({ url: 'javascript:alert(1)' }));
    expect(screen.queryByRole('link', { name: /Open original/ })).not.toBeInTheDocument();
    await settle();
  });

  it('links the title to the detail page', async () => {
    view(makeCard());
    expect(screen.getByRole('link', { name: 'Acme Translator' })).toHaveAttribute('href', '/resource/11111111-1111-5111-8111-111111111111');
    await settle();
  });

  it('detail: renders three distinct dates; UNKNOWN is the word Unknown, never blank', async () => {
    view(makeCard({ timeline: { published: unknownDate, updated: known('2026-09-01T00:00:00Z'), discovered: unknownDate } }), true);
    const tl = screen.getByRole('group', { name: 'Dates' });
    const rows = within(tl).getAllByText(/Published|Last updated|Discovered by Helper2/);
    expect(rows).toHaveLength(3);
    expect(within(tl).getByText('Published').nextElementSibling).toHaveTextContent('Unknown');
    expect(within(tl).getByText('Discovered by Helper2').nextElementSibling).toHaveTextContent('Unknown');
    expect(within(tl).getByText('Last updated').nextElementSibling).toHaveTextContent('Sep 1, 2026');
    await settle();
  });

  it('KNOWN status without value still says Unknown (no guessed date)', async () => {
    view(makeCard({ timeline: { published: { status: 'KNOWN', precision: 'DAY', value: null }, updated: unknownDate, discovered: unknownDate } }), true);
    expect(within(screen.getByRole('group', { name: 'Dates' })).getAllByText('Unknown')).toHaveLength(3);
    await settle();
  });

  it('CONFLICTING dates show the chosen value and the alternatives with source ids', async () => {
    view(makeCard({
      timeline: {
        published: { status: 'CONFLICTING', precision: 'INSTANT', value: '2025-01-01T00:00:00Z', sourceId: 'github', alternatives: [{ value: '2025-02-02T00:00:00Z', sourceId: 'hf' }] },
        updated: unknownDate, discovered: unknownDate,
      },
    }), true);
    const pub = screen.getByText('Published').nextElementSibling as HTMLElement;
    expect(pub).toHaveTextContent('Sources disagree');
    expect(pub).toHaveTextContent('Jan 1, 2025');
    expect(pub).toHaveTextContent('source: github');
    expect(pub).toHaveTextContent('Feb 2, 2025');
    expect(pub).toHaveTextContent('source: hf');
    await settle();
  });

  it('popularity is a labelled metric with source and date, never a combined score', async () => {
    view(makeCard());
    expect(screen.getByText('1.2K GitHub stars · GitHub · as of Oct 20, 2026')).toBeInTheDocument();
    await settle();
  });

  it('shows description origin when it is not the source itself', async () => {
    view(makeCard({ descriptionOrigin: 'CURATED' }));
    expect(screen.getByText('Curated by Helper2')).toBeInTheDocument();
    await settle();
  });

  it('shows a conflicts indicator', async () => {
    view(makeCard({ conflicts: [{ field: 'license', values: [{ value: 'MIT', sourceId: 'github' }, { value: 'Apache-2.0', sourceId: 'hf' }] }] }));
    expect(screen.getByText('Sources disagree on license')).toBeInTheDocument();
    expect(screen.getByText(/Apache-2.0 \(hf\)/)).toBeInTheDocument();
    await settle();
  });

  it('source mode badges are text', async () => {
    view(makeCard({ sources: [{ sourceId: 's', sourceName: 'Seed', url: 'https://x.example', mode: 'CURATED', retrievedAt: '2026-10-20T10:00:00Z' }] }));
    expect(screen.getByText('Curated', { selector: '.mode' })).toBeInTheDocument();
    await settle();
  });

  it('one order for everyone: description, then use facts, then build facts', async () => {
    const order = (c: HTMLElement) => Array.from(c.querySelectorAll('.card > .card-desc, .card > [data-block]')).map((e) => (e as HTMLElement).dataset['block'] ?? 'desc');
    const { container } = view(makeCard());
    expect(screen.getByText('Pricing')).toBeInTheDocument();
    expect(screen.getByText('License')).toBeInTheDocument();
    expect(order(container)).toEqual(['desc', 'use', 'build']);
    await settle();
  });

  it('result card shows only known facts, a count of unknown ones and a link to all facts', async () => {
    view(makeCard({ license: null, openSource: null, pricing: null, platforms: [] }));
    expect(screen.queryByText('License')).not.toBeInTheDocument();
    expect(screen.queryByText('Pricing')).not.toBeInTheDocument();
    expect(screen.queryByText('Unknown')).not.toBeInTheDocument();
    expect(screen.getByText('Technical level')).toBeInTheDocument();
    expect(screen.getByText(/4 facts not stated by any source/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'All facts' })).toHaveAttribute('href', '/resource/11111111-1111-5111-8111-111111111111');
    await settle();
  });

  it('result card shows only the last-updated date; detail shows all dates', async () => {
    const { unmount } = view(makeCard());
    const tl = screen.getByRole('group', { name: 'Dates' });
    expect(within(tl).getByText('Last updated')).toBeInTheDocument();
    expect(within(tl).queryByText('Published')).not.toBeInTheDocument();
    unmount();
    view(makeCard(), true);
    expect(within(screen.getByRole('group', { name: 'Dates' })).getByText('Published')).toBeInTheDocument();
    await settle();
  });

  it('detail shows every fact, Unknown included, plus audience and source records', async () => {
    view(makeCard({ license: null }), true);
    expect(screen.getByText('License').closest('div')).toHaveTextContent('Unknown');
    expect(screen.getByText('Audience')).toBeInTheDocument();
    expect(screen.getByText('Source records')).toBeInTheDocument();
    await settle();
  });

  it('the type badge has an inline definition on every card, not only on detail', async () => {
    view(makeCard({ type: 'MCP_SERVER' }));
    expect(screen.getByRole('button', { name: /MCP server/ })).toBeInTheDocument();
    await settle();
  });

  it('every type badge says that Helper2 assigned the type, including types without a dev term', async () => {
    for (const type of ['MCP_SERVER', 'PRODUCT', 'ARTICLE', 'OTHER'] as const) {
      const { unmount } = view(makeCard({ type }));
      await userEvent.click(screen.getByRole('button', { name: new RegExp(`^${type === 'MCP_SERVER' ? 'MCP server' : type.charAt(0) + type.slice(1).toLowerCase()}`) }));
      expect(screen.getByRole('note')).toHaveTextContent(/Helper2 assigns the type .* so it can be wrong\./);
      unmount();
    }
    await settle();
  });

  it('shows a Save button on each card', async () => {
    view(makeCard());
    expect(screen.getByRole('button', { name: /Save Acme Translator/ })).toBeInTheDocument();
    await settle();
  });

  it('the bookmark toggle is a pressed button', async () => {
    render(<BookmarksProvider><BookmarkButton card={makeCard()} /></BookmarksProvider>);
    const btn = screen.getByRole('button', { name: /Save Acme Translator/ });
    expect(btn).toHaveAttribute('aria-pressed', 'false');
    await userEvent.click(btn);
    expect(btn).toHaveAttribute('aria-pressed', 'true');
    expect(btn).toHaveTextContent('Saved');
    await settle();
  });

  it('technical terms have keyboard-accessible definitions', async () => {
    view(makeCard({ type: 'MCP_SERVER' }), true);
    const btn = screen.getByRole('button', { name: /MCP server/ });
    expect(btn).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText(/Model Context Protocol/)).not.toBeInTheDocument();
    btn.focus();
    await userEvent.keyboard('{Enter}');
    expect(btn).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText(/Model Context Protocol/)).toBeInTheDocument();
    await userEvent.keyboard(' ');
    expect(btn).toHaveAttribute('aria-expanded', 'false');
    await settle();
  });
});

describe('ResultCardView compatibility flags', () => {
  it('shows each flag with the reason it was derived', async () => {
    view(makeCard({ compatibleWith: [
      { tool: 'openai', label: 'OpenAI', basis: 'TAG', evidence: 'openai', sourceId: 'npm' },
      { tool: 'mcp', label: 'Any MCP client', basis: 'TYPE', evidence: 'MCP_SERVER', sourceId: null },
    ] }));
    const list = screen.getByRole('list', { name: 'Works with' });
    const openai = within(list).getByText('OpenAI').closest('.compat-chip') as HTMLElement;
    expect(openai).toHaveAttribute('title', 'npm lists the tag “openai”');
    expect(openai.closest('a')).toBeNull();
    expect(within(list).getByText('Any MCP client').closest('a')).toBeNull();
    await settle();
  });

  it('renders nothing when no flag applies', async () => {
    view(makeCard());
    expect(screen.queryByRole('list', { name: 'Works with' })).toBeNull();
    await settle();
  });
});
