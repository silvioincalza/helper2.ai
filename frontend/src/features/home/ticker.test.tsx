import { act, fireEvent, render, screen } from '@testing-library/react';
import { useRef } from 'react';
import { SCROLL_REST_MS, TICK_EVERY_MS, tickerWindow, useTicker } from './ticker';

vi.mock('./useRadarMotion', () => ({ useRadarStill: () => still }));
let still = false;

describe('tickerWindow', () => {
  const rows = ['a', 'b', 'c', 'd'];

  it('shows [count] rows from the offset, wrapping round to the start', () => {
    expect(tickerWindow(rows, 0, 3)).toEqual(['a', 'b', 'c']);
    expect(tickerWindow(rows, 2, 3)).toEqual(['c', 'd', 'a']);
    expect(tickerWindow(rows, 5, 3)).toEqual(['b', 'c', 'd']);
  });

  it('shows every row when there are no more than it shows', () => {
    expect(tickerWindow(['a', 'b'], 7, 3)).toEqual(['a', 'b']);
  });
});

function Board({
  active = true,
  holdOnScroll = false,
}: {
  active?: boolean;
  holdOnScroll?: boolean;
}) {
  const box = useRef<HTMLDivElement>(null);
  const t = useTicker(box, active, { holdOnScroll });
  return (
    <>
      <div ref={box} data-testid="box" {...t.handlers}>
        <p data-testid="offset">{t.offset}</p>
        <a href="#x">A row</a>
      </div>
    </>
  );
}

describe('useTicker', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    still = false;
  });
  afterEach(() => vi.useRealTimers());
  const tick = () => act(() => vi.advanceTimersByTime(TICK_EVERY_MS));
  const offset = () => screen.getByTestId('offset').textContent;

  it('moves one row every few seconds', () => {
    render(<Board />);
    tick();
    tick();
    expect(offset()).toBe('2');
  });

  it('holds while the pointer is over it and while the focus is in it', () => {
    render(<Board />);
    fireEvent.mouseEnter(screen.getByTestId('box'));
    tick();
    expect(offset()).toBe('0');
    fireEvent.mouseLeave(screen.getByTestId('box'));
    act(() => screen.getByRole('link').focus());
    tick();
    expect(offset()).toBe('0');
    act(() => screen.getByRole('link').blur());
    tick();
    expect(offset()).toBe('1');
  });

  it('a tap holds it and a second tap starts it again; a tap on a link does not count', () => {
    render(<Board />);
    fireEvent.click(screen.getByTestId('offset'));
    tick();
    expect(offset()).toBe('0');
    fireEvent.click(screen.getByRole('link'));
    tick();
    expect(offset()).toBe('0');
    fireEvent.click(screen.getByTestId('offset'));
    tick();
    expect(offset()).toBe('1');
  });

  it('on a phone it holds while the page scrolls, and moves again a second after', () => {
    render(<Board holdOnScroll />);
    act(() => {
      window.dispatchEvent(new Event('scroll'));
    });
    act(() => vi.advanceTimersByTime(SCROLL_REST_MS - 1));
    expect(offset()).toBe('0');
    act(() => vi.advanceTimersByTime(1));
    tick();
    expect(offset()).toBe('1');
  });

  it('never moves under reduced motion or with motion off, nor when the list fits', () => {
    still = true;
    const { unmount } = render(<Board />);
    tick();
    expect(offset()).toBe('0');
    unmount();
    still = false;
    render(<Board active={false} />);
    tick();
    expect(offset()).toBe('0');
  });
});
