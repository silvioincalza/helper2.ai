import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { render, screen, fireEvent } from '@testing-library/react';
import { AiLabel, bubbleAt } from './AiLabel';

const screenSize = { width: 375, height: 812 };
const bubble = { width: 120, height: 24 };

describe('Feature: the words of an AI mark show where the screen has room', () => {
  it('Scenario: above the mark, centred on it', () => {
    const at = bubbleAt({ left: 180, top: 300, right: 204, bottom: 324 }, bubble, screenSize);
    expect(at).toEqual({ left: 132, top: 272 });
  });

  it('Scenario: below the mark when there is no room above', () => {
    const at = bubbleAt({ left: 180, top: 10, right: 204, bottom: 34 }, bubble, screenSize);
    expect(at.top).toBe(38);
  });

  it('Scenario: never past the left or the right edge of the screen', () => {
    expect(bubbleAt({ left: 0, top: 300, right: 24, bottom: 324 }, bubble, screenSize).left).toBe(
      8,
    );
    expect(
      bubbleAt({ left: 351, top: 300, right: 375, bottom: 324 }, bubble, screenSize).left,
    ).toBe(375 - 120 - 8);
  });

  it('Scenario: pointing at the mark, focusing it or tapping it shows its words; leaving hides them', () => {
    render(<AiLabel kind="estimated">AI-estimated</AiLabel>);
    const mark = screen.getByRole('button', { name: 'AI-estimated' });
    expect(mark).not.toHaveClass('is-open');
    fireEvent.mouseEnter(mark);
    expect(mark).toHaveClass('is-open');
    fireEvent.mouseLeave(mark);
    expect(mark).not.toHaveClass('is-open');
    fireEvent.click(mark);
    expect(mark).toHaveClass('is-open');
    fireEvent.blur(mark);
    expect(mark).not.toHaveClass('is-open');
  });

  it('Scenario: inside an example chip, focusing the chip shows the words; leaving it hides them', () => {
    render(
      <button type="button">
        Served 1 <AiLabel inControl>AI draft</AiLabel>
      </button>,
    );
    const chip = screen.getByRole('button', { name: 'Served 1 AI draft' });
    const mark = screen.getByText('AI draft').closest('.ai-label')!;
    expect(mark).not.toHaveClass('is-open');
    fireEvent.focus(chip);
    expect(mark).toHaveClass('is-open');
    fireEvent.blur(chip);
    expect(mark).not.toHaveClass('is-open');
  });

  it('Scenario: on a touch screen the words of a mark inside a chip are shown next to the dot', () => {
    const css = readFileSync(join(process.cwd(), 'src/styles/app.css'), 'utf8');
    expect(css).toMatch(
      /@media \(hover: none\) \{[^@]*\.ai-label--in-control \.ai-label__words \{[^}]*position: static;[^}]*clip: auto;/,
    );
  });
});
