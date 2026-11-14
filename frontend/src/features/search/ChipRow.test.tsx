import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ChipRow } from './ChipRow';

function sized(row: HTMLElement, { scrollWidth, clientWidth }: Record<string, number>) {
  Object.defineProperty(row, 'scrollWidth', { configurable: true, value: scrollWidth });
  Object.defineProperty(row, 'clientWidth', { configurable: true, value: clientWidth });
}
function scrollTo(row: HTMLElement, left: number) {
  row.scrollLeft = left;
  fireEvent.scroll(row);
}

function view(chips = ['Coding', 'Chat', 'Automation']) {
  const utils = render(
    <ChipRow label="Narrow by use case:">
      {chips.map((c) => (
        <button key={c} type="button" className="chip">
          {c}
        </button>
      ))}
    </ChipRow>,
  );
  return { ...utils, row: screen.getByRole('group', { name: 'Narrow by use case:' }) };
}

describe('Feature: a row of chips that scrolls sideways shows that there is more', () => {
  it('Scenario: all the chips fit: no side is marked', () => {
    const { row } = view();
    sized(row, { scrollWidth: 343, clientWidth: 343 });
    scrollTo(row, 0);
    expect(row).not.toHaveAttribute('data-more-start');
    expect(row).not.toHaveAttribute('data-more-end');
  });

  it('Scenario: chips are cut on the right: the right side is marked; scrolled to the middle, both; at the end, only the left', () => {
    const { row } = view();
    sized(row, { scrollWidth: 547, clientWidth: 359 });
    scrollTo(row, 0);
    expect(row).not.toHaveAttribute('data-more-start');
    expect(row).toHaveAttribute('data-more-end');
    scrollTo(row, 60);
    expect(row).toHaveAttribute('data-more-start');
    expect(row).toHaveAttribute('data-more-end');
    scrollTo(row, 188);
    expect(row).toHaveAttribute('data-more-start');
    expect(row).not.toHaveAttribute('data-more-end');
  });

  it('Scenario: a chip leaves the row and the rest fit: the mark goes without a scroll', () => {
    const { row, rerender } = view();
    sized(row, { scrollWidth: 547, clientWidth: 359 });
    scrollTo(row, 0);
    expect(row).toHaveAttribute('data-more-end');
    sized(row, { scrollWidth: 359, clientWidth: 359 });
    rerender(
      <ChipRow label="Narrow by use case:">
        <button type="button" className="chip">
          Coding
        </button>
      </ChipRow>,
    );
    expect(row).not.toHaveAttribute('data-more-end');
  });

  it('Scenario: Tab reaches every chip in order, and the chip under the focus is brought into view', async () => {
    const intoView = vi.fn();
    Element.prototype.scrollIntoView = intoView;
    view();
    for (const name of ['Coding', 'Chat', 'Automation']) {
      await userEvent.tab();
      expect(screen.getByRole('button', { name })).toHaveFocus();
      expect(intoView).toHaveBeenLastCalledWith({ block: 'nearest', inline: 'nearest' });
      expect(intoView.mock.contexts.at(-1)).toBe(screen.getByRole('button', { name }));
    }
  });
});
