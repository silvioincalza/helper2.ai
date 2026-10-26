import { render } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { routes } from '@/routes';

export function renderApp(entry = '/') {
  const router = createMemoryRouter(routes, { initialEntries: [entry] });
  const utils = render(<RouterProvider router={router} />);
  return { router, ...utils, params: () => new URLSearchParams(router.state.location.search) };
}
