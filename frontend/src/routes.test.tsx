import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { mockApi } from '@/test/mockApi';
import { routes } from './routes';

describe('routes', () => {
  it('loads a page outside the home bundle on its first visit', async () => {
    mockApi();
    const router = createMemoryRouter(routes, { initialEntries: ['/privacy'] });
    render(<RouterProvider router={router} />);
    expect(await screen.findByRole('heading', { level: 1, name: 'Privacy' })).toBeInTheDocument();
    expect(routes[0]?.children?.find((r) => r.path === 'privacy')?.lazy).toBeTypeOf('function');
  });
});
