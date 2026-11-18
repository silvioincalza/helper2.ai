import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { BackLink } from './BackLink';

function renderAt(entries: string[], from: string | undefined) {
  const router = createMemoryRouter(
    [
      { path: '/list', element: <p>List</p> },
      { path: '/home', element: <p>Home</p> },
      {
        path: '/page',
        element: (
          <BackLink from={from} fallback="/home">
            Back
          </BackLink>
        ),
      },
    ],
    { initialEntries: entries },
  );
  render(<RouterProvider router={router} />);
  return router;
}

describe('BackLink', () => {
  it('goes one step back in history when the page was opened from the list', async () => {
    const router = renderAt(['/list?q=mcp', '/page'], '/list?q=mcp');
    expect(screen.getByRole('link', { name: 'Back' })).toHaveAttribute('href', '/list?q=mcp');
    await userEvent.click(screen.getByRole('link', { name: 'Back' }));
    expect(await screen.findByText('List')).toBeInTheDocument();
    expect(router.state.historyAction).toBe('POP');
  });

  it.each([
    ['alt', { altKey: true }],
    ['ctrl', { ctrlKey: true }],
    ['meta', { metaKey: true }],
    ['shift', { shiftKey: true }],
    ['middle button', { button: 1 }],
  ])('leaves a %s click to the browser', (_name, init) => {
    const router = renderAt(['/list', '/page'], '/list');
    fireEvent.click(screen.getByRole('link', { name: 'Back' }), init);
    expect(router.state.location.pathname).toBe('/page');
  });

  it('opens the list as a new step when the page is the first entry in history', async () => {
    const router = renderAt(['/page'], '/list');
    await userEvent.click(screen.getByRole('link', { name: 'Back' }));
    expect(await screen.findByText('List')).toBeInTheDocument();
    expect(router.state.historyAction).toBe('PUSH');
  });

  it('links to the fallback page when no list is known', () => {
    renderAt(['/list', '/page'], undefined);
    expect(screen.getByRole('link', { name: 'Back' })).toHaveAttribute('href', '/home');
  });
});
