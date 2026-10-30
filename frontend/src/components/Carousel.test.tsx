import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Carousel } from './Carousel';

const ITEMS = ['a', 'b', 'c', 'd', 'e'];

function renderCarousel(items = ITEMS) {
  return render(<Carousel items={items} label="Radar" render={(x) => <li key={x}><a href={`#${x}`}>{x}</a></li>} />);
}

describe('carousel', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    Element.prototype.scrollTo = vi.fn();
  });
  afterEach(() => {
    vi.useRealTimers();
    delete (Element.prototype as Partial<Element>).scrollTo;
  });

  it('moves by itself, and Pause stops it until Play', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderCarousel();
    const scrollTo = vi.mocked(Element.prototype.scrollTo);

    act(() => vi.advanceTimersByTime(6000));
    expect(scrollTo).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: 'Pause Radar' }));
    await user.unhover(screen.getByRole('button', { name: 'Play Radar' }));
    (document.activeElement as HTMLElement | null)?.blur();
    act(() => vi.advanceTimersByTime(18000));
    expect(scrollTo).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: 'Play Radar' }));
    (document.activeElement as HTMLElement | null)?.blur();
    await user.unhover(screen.getByRole('button', { name: 'Pause Radar' }));
    act(() => vi.advanceTimersByTime(6000));
    expect(scrollTo).toHaveBeenCalledTimes(2);
  });

  it('has no Pause button when everything fits', () => {
    renderCarousel(['a', 'b']);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('has no Pause button for reduced motion, because it never moves by itself', () => {
    vi.stubGlobal('matchMedia', (q: string) => ({ matches: q.includes('reduce'), media: q, addEventListener: () => undefined, removeEventListener: () => undefined }));
    renderCarousel();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});
