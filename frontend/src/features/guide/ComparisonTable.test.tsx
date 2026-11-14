import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import type { GuideResponse } from '@/api/types';
import { upTo } from '@/lib/breakpoints';
import { ComparisonTable } from './ComparisonTable';

const comparison: NonNullable<GuideResponse['comparison']> = {
  subjects: [
    {
      key: 's1',
      name: 'Ollama',
      resourceId: '11111111-1111-5111-8111-111111111111',
      title: 'ollama',
      url: 'https://github.com/ollama/ollama',
    },
    { key: 's2', name: 'LM Studio', resourceId: null, title: null, url: null },
  ],
  rows: [
    {
      aspect: 'Licence',
      cells: [
        { subjectKey: 's1', text: 'MIT', quote: 'MIT License', reasonCheck: 'SUPPORTED' },
        { subjectKey: 's2', text: null, quote: null, reasonCheck: 'NOT_CHECKED' },
      ],
    },
  ],
};

const css = readFileSync(join(process.cwd(), 'src/styles/app.css'), 'utf8');
const phone = [
  ...css.matchAll(
    new RegExp(
      `@media ${upTo('phone').replace(/[()]/g, '\\$&')} \\{((?:[^{}]*\\{[^}]*\\})*)\\s*\\}`,
      'g',
    ),
  ),
]
  .map((m) => m[1])
  .join('\n');
const onPhone = (selector: string) =>
  [...phone.matchAll(/([^{}]+)\{([^{}]*)\}/g)]
    .filter((m) => m[1]!.trim().split(/,\s*/).includes(selector))
    .map((m) => m[2])
    .join('\n');

function view() {
  render(
    <MemoryRouter>
      <ComparisonTable comparison={comparison} />
    </MemoryRouter>,
  );
  return screen.getByRole('table');
}

describe('Feature: the comparison reads on a phone (WCAG 1.4.10)', () => {
  it('Scenario: every answer has the name of its tool above it, so a card per aspect reads without the column heads', () => {
    const table = view();
    const [ollama, lmStudio] = within(table).getAllByRole('cell');
    expect(within(ollama!).getByText('ollama')).toHaveClass('guide-compare__tool');
    expect(within(lmStudio!).getByText('LM Studio')).toHaveClass('guide-compare__tool');
    expect(ollama).toHaveTextContent('MIT');
    expect(lmStudio).toHaveTextContent('Not stated by the source');
  });

  it('Scenario: a screen reader hears the name once, from the column head, not from the label in the card', () => {
    const table = view();
    for (const label of table.querySelectorAll('.guide-compare__tool'))
      expect(label).toHaveAttribute('aria-hidden', 'true');
    expect(within(table).getByRole('columnheader', { name: 'ollama' })).toBeInTheDocument();
    expect(within(table).getByRole('rowheader', { name: 'Licence' })).toBeInTheDocument();
  });

  it('Scenario: it stays a table when the phone layout stacks it: every part says its role itself', () => {
    const table = view();
    expect(table).toHaveAttribute('role', 'table');
    expect(table.querySelectorAll('thead[role="rowgroup"], tbody[role="rowgroup"]')).toHaveLength(
      2,
    );
    expect(table.querySelectorAll('tr:not([role="row"])')).toHaveLength(0);
    expect(table.querySelectorAll('thead th:not([role="columnheader"])')).toHaveLength(0);
    expect(table.querySelectorAll('tbody th:not([role="rowheader"])')).toHaveLength(0);
    expect(table.querySelectorAll('td:not([role="cell"])')).toHaveLength(0);
  });

  it('Scenario: the AI mark, the label, the quote and the link to the tool are all still there', () => {
    const table = view();
    expect(within(table).getByText('MIT')).toHaveAttribute('data-ai-generated', 'true');
    expect(within(table).getByText('Licence')).toHaveAttribute('data-ai-generated', 'true');
    expect(within(table).getByText('MIT License').tagName).toBe('Q');
    expect(within(table).getByText('MIT License').closest('[data-ai-generated]')).toBeNull();
    expect(within(table).getByRole('link', { name: 'ollama' })).toHaveAttribute(
      'href',
      '/resource/11111111-1111-5111-8111-111111111111/ollama',
    );
    expect(screen.getByRole('heading', { name: 'Side by side AI-written' })).toBeInTheDocument();
    expect(screen.getByText(/Not found in our sources/)).toBeInTheDocument();
  });

  it('Scenario: the note under it says what the screen shows: the quote is under the answer, nothing to hover or open', () => {
    view();
    expect(
      screen.getByText(
        "Each answer is AI-written. The quote under it is that resource's own text.",
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText(/hover/)).not.toBeInTheDocument();
  });

  it('Scenario: on a phone the table is a stack of cards, one per aspect, and does not scroll sideways', () => {
    expect(onPhone('.guide-compare')).toMatch(/overflow-x:\s*visible/);
    for (const part of ['table', 'thead', 'tbody', 'tr', 'th', 'td'])
      expect(onPhone(`.guide-compare ${part}`)).toMatch(/display:\s*block/);
    const card = onPhone('.guide-compare tbody tr');
    expect(card).toMatch(/border:\s*1px solid var\(--border\)/);
    expect(card).toMatch(/background:\s*var\(--surface\)/);
    expect(onPhone('.guide-compare__tool')).toMatch(/display:\s*block/);
    expect(css).toMatch(/\n\.guide-compare__tool \{\s*display: none;/);
  });

  it('Scenario: on a phone the links to the tools stay in sight above the cards, each 44px tall; only the word "Aspect" is hidden', () => {
    expect(onPhone('.guide-compare thead tr')).toMatch(/display:\s*flex/);
    expect(onPhone('.guide-compare thead a')).toMatch(/min-height:\s*var\(--target-min\)/);
    expect(onPhone('.guide-compare thead th')).toMatch(/white-space:\s*normal/);
    expect(onPhone('.guide-compare .guide-compare__aspect')).toMatch(/clip:\s*rect\(0, 0, 0, 0\)/);
    expect(onPhone('.guide-compare .guide-compare__aspect')).not.toMatch(/display:\s*none/);
  });
});
