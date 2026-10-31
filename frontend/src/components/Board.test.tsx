import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import '@/i18n';
import { Board, type BoardRow } from './Board';

const row = (n: number): BoardRow => ({
  key: `r${n}`, when: `2${n}.10`, tag: <span>Model</span>, where: 'GitHub', status: <span>Updated</span>, name: `Row ${n}`, to: `/resource/${n}`,
});

function renderBoard(rows: BoardRow[]) {
  const router = createMemoryRouter([{
    path: '/',
    element: <Board id="radar" title="News" subtitle="Last 7 days" footnote="Dates as each source states them" rows={rows}
      loading={false} error={false} empty="Nothing" onRetry={() => undefined} />,
  }]);
  return render(<RouterProvider router={router} />);
}

describe('home board', () => {
  it('loops a long list with a second copy that assistive tech and the keyboard skip', () => {
    renderBoard([1, 2, 3, 4, 5, 6].map(row));
    // One list for screen readers, each row once.
    const list = screen.getByRole('list', { name: 'News' });
    expect(within(list).getAllByRole('listitem')).toHaveLength(6);
    expect(screen.getAllByRole('link')).toHaveLength(6);
    const copy = document.querySelector('ul[aria-hidden="true"]');
    expect(copy?.querySelectorAll('li')).toHaveLength(6);
    for (const a of copy?.querySelectorAll('a') ?? []) expect(a).toHaveAttribute('tabindex', '-1');
    expect(document.querySelector('.board-body')).toHaveClass('is-looping');
  });

  it('a short list stands still and is drawn once', () => {
    renderBoard([1, 2].map(row));
    expect(document.querySelector('ul[aria-hidden="true"]')).toBeNull();
    expect(document.querySelector('.board-body')).not.toHaveClass('is-looping');
  });

  it('a tap on the board pauses it and says so; another tap plays it', async () => {
    renderBoard([1, 2, 3, 4, 5, 6].map(row));
    const body = document.querySelector('.board-body') as HTMLElement;
    await userEvent.click(within(body).getAllByText('GitHub')[0] as HTMLElement);
    expect(screen.getByRole('status')).toHaveTextContent('Paused · tap to play');
    expect(screen.getByRole('region', { name: /News/ })).toHaveClass('is-paused');
    await userEvent.click(within(body).getAllByText('GitHub')[0] as HTMLElement);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});
