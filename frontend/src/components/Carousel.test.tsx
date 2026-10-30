import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Carousel } from './Carousel';

const ITEMS = ['one', 'two', 'three', 'four', 'five'];
const show = () => render(<Carousel items={ITEMS} label="What’s new" render={(x) => <li key={x}>{x}</li>} />);

describe('Carousel', () => {
  let scrolled: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    scrolled = vi.fn();
    Element.prototype.scrollTo = scrolled as unknown as Element['scrollTo'];
  });
  afterEach(() => {
    vi.useRealTimers();
    delete (Element.prototype as Partial<Element>).scrollTo;
  });

  it('moves by itself until the visible pause button stops it, and play starts it again', async () => {
    show();
    act(() => vi.advanceTimersByTime(6000));
    expect(scrolled).toHaveBeenCalledTimes(1);

    const pause = screen.getByRole('button', { name: 'Pause What’s new' });
    await userEvent.click(pause);
    // Focus is still on the button, so leave the carousel before checking that the pause alone holds it.
    act(() => pause.blur());
    act(() => vi.advanceTimersByTime(18000));
    expect(scrolled).toHaveBeenCalledTimes(1);

    await userEvent.click(screen.getByRole('button', { name: 'Play What’s new' }));
    act(() => screen.getByRole('button', { name: 'Pause What’s new' }).blur());
    act(() => vi.advanceTimersByTime(6000));
    expect(scrolled).toHaveBeenCalledTimes(2);
  });

  it('shows no pause button when the list fits and never moves', () => {
    render(<Carousel items={['one']} label="What’s new" render={(x) => <li key={x}>{x}</li>} />);
    expect(screen.queryByRole('button', { name: /Pause/ })).not.toBeInTheDocument();
  });
});
