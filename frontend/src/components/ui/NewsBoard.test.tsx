import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { axeViolations } from '@/test/a11y';
import { NewsBoard, type BoardRow } from './NewsBoard';
import { StatusPill } from './StatusPill';
import { TypeTag } from './TypeTag';

const row = (n: number): BoardRow => ({
  id: `r${n}`,
  date: <time dateTime="2026-10-20">Oct 20, 2026</time>,
  tag: <TypeTag type="MCP_SERVER" />,
  where: 'GitHub',
  status: <StatusPill tone="ok">Published</StatusPill>,
  name: `Server ${n}`,
  to: `/resource/r${n}`,
});
const ROWS = [1, 2, 3, 4, 5, 6].map(row);

function renderBoard(props: Partial<Parameters<typeof NewsBoard>[0]> = {}) {
  const onRetry = vi.fn();
  const router = createMemoryRouter([{
    path: '/',
    element: <NewsBoard title="News" lede="New this week." rows={ROWS} failed={false} onRetry={onRetry}
      emptyText="Nothing new." errorText="Could not load the news." {...props} />,
  }]);
  return { ...render(<RouterProvider router={router} />), onRetry };
}

const scrolls = () => vi.mocked(Element.prototype.scrollTo).mock.calls.length;
const tick = () => act(() => vi.advanceTimersByTime(4000));

describe('a News or Events board', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    Element.prototype.scrollTo = vi.fn();
  });
  afterEach(() => {
    vi.useRealTimers();
    delete (Element.prototype as Partial<Element>).scrollTo;
  });

  it('shows each row as date, type tag, source or place, status pill and a link to the resource', async () => {
    const { container } = renderBoard();
    const board = screen.getByRole('region', { name: 'News' });
    expect(within(board).getByText('New this week.')).toBeInTheDocument();
    const rows = within(board).getAllByRole('listitem');
    expect(rows).toHaveLength(6);
    expect(rows[0]).toHaveTextContent('Oct 20, 2026');
    expect(rows[0]).toHaveTextContent('MCP server');
    expect(rows[0]).toHaveTextContent('GitHub');
    expect(rows[0]).toHaveTextContent('Published');
    expect(within(rows[0] as HTMLElement).getByRole('link', { name: 'Server 1' })).toHaveAttribute('href', '/resource/r1');
    expect(await axeViolations(container)).toEqual([]);
  });

  it('scrolls by itself one row at a time, and Pause stops it until Play (WCAG 2.2.2)', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderBoard();
    tick();
    expect(scrolls()).toBe(1);

    await user.click(screen.getByRole('button', { name: 'Pause News' }));
    await user.unhover(screen.getByRole('button', { name: 'Play News' }));
    act(() => (document.activeElement as HTMLElement | null)?.blur());
    tick(); tick();
    expect(scrolls()).toBe(1);

    await user.click(screen.getByRole('button', { name: 'Play News' }));
    await user.unhover(screen.getByRole('button', { name: 'Pause News' }));
    act(() => (document.activeElement as HTMLElement | null)?.blur());
    tick();
    expect(scrolls()).toBe(2);
  });

  it('holds still while the pointer is over it', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderBoard();
    await user.hover(screen.getByRole('list', { name: 'News' }));
    tick(); tick();
    expect(scrolls()).toBe(0);
    await user.unhover(screen.getByRole('list', { name: 'News' }));
    tick();
    expect(scrolls()).toBe(1);
  });

  it('holds still while keyboard focus is inside', () => {
    renderBoard();
    act(() => screen.getByRole('link', { name: 'Server 2' }).focus());
    tick(); tick();
    expect(scrolls()).toBe(0);
    act(() => screen.getByRole('link', { name: 'Server 2' }).blur());
    tick();
    expect(scrolls()).toBe(1);
  });

  it('a tap pauses it and a second tap plays it again; the button says which', () => {
    renderBoard();
    const list = screen.getByRole('list', { name: 'News' });
    fireEvent.pointerUp(list, { pointerType: 'touch' });
    expect(screen.getByRole('button', { name: 'Play News' })).toBeInTheDocument();
    tick();
    expect(scrolls()).toBe(0);
    fireEvent.pointerUp(list, { pointerType: 'touch' });
    expect(screen.getByRole('button', { name: 'Pause News' })).toBeInTheDocument();
    tick();
    expect(scrolls()).toBe(1);
  });

  it('a tap on a link opens it and does not toggle the board', () => {
    renderBoard();
    fireEvent.pointerUp(screen.getByRole('link', { name: 'Server 3' }), { pointerType: 'touch' });
    expect(screen.getByRole('button', { name: 'Pause News' })).toBeInTheDocument();
  });

  it('with reduced motion it never moves, has no Pause button and scrolls by hand', () => {
    vi.stubGlobal('matchMedia', (q: string) => ({ matches: q.includes('reduce'), media: q, addEventListener: () => undefined, removeEventListener: () => undefined }));
    renderBoard();
    tick(); tick();
    expect(scrolls()).toBe(0);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'News' })).toHaveClass('h2-board__list');
  });

  it('a screen reader gets a normal list: one copy of each row, never announced as it moves', () => {
    renderBoard();
    const list = screen.getByRole('list', { name: 'News' });
    expect(list).toHaveAttribute('aria-live', 'off');
    expect(screen.getAllByRole('link', { name: 'Server 1' })).toHaveLength(1);
  });

  it('has no Pause button when every row fits', () => {
    renderBoard({ rows: ROWS.slice(0, 3) });
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('while loading, shows skeleton rows and says so once', async () => {
    const { container } = renderBoard({ rows: undefined });
    expect(screen.getByText('Loading…')).toHaveClass('sr-only');
    expect(container.querySelectorAll('.skeleton-row')).toHaveLength(4);
    expect(container.querySelector('[aria-busy="true"]')).not.toBeNull();
    expect(await axeViolations(container)).toEqual([]);
  });

  it('when empty, says so', () => {
    renderBoard({ rows: [] });
    expect(screen.getByText('Nothing new.')).toBeInTheDocument();
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
  });

  it('on error, says so and offers to try again', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const { onRetry, container } = renderBoard({ rows: undefined, failed: true });
    expect(screen.getByRole('alert')).toHaveTextContent('Could not load the news.');
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledOnce();
    expect(await axeViolations(container)).toEqual([]);
  });
});
