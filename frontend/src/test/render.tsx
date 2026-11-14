import { act, render } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { clearApiCache } from '@/hooks/useApi';
import { clearPolicyCache } from '@/hooks/usePolicy';
import { adminRoutes } from '@/admin/routes';
import { routes } from '@/routes';

export function renderApp(entry = '/') {
  clearApiCache();
  clearPolicyCache();
  const router = createMemoryRouter(routes, { initialEntries: [entry] });
  const utils = render(<RouterProvider router={router} />);
  return { router, ...utils, params: () => new URLSearchParams(router.state.location.search) };
}

export function renderAdmin(entry = '/admin') {
  clearApiCache();
  clearPolicyCache();
  const router = createMemoryRouter(adminRoutes, { initialEntries: [entry] });
  const utils = render(<RouterProvider router={router} />);
  return { router, ...utils, params: () => new URLSearchParams(router.state.location.search) };
}

export async function settle() {
  await act(async () => {
    for (let i = 0; i < 3; i++) await new Promise((resolve) => setTimeout(resolve, 0));
  });
}
