import { act, render } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { clearApiCache } from '@/hooks/useApi';
import { routes } from '@/routes';

/** A fresh page load: its own router and an empty response cache. */
export function renderApp(entry = '/') {
  clearApiCache();
  const router = createMemoryRouter(routes, { initialEntries: [entry] });
  const utils = render(<RouterProvider router={router} />);
  return { router, ...utils, params: () => new URLSearchParams(router.state.location.search) };
}

/**
 * Lets the answers a test did not wait for (the sources list, /me, a list in the background) arrive inside act(),
 * so they update the screen as in the browser instead of after the test.
 */
export async function settle() {
  await act(async () => {
    for (let i = 0; i < 3; i++) await new Promise((resolve) => setTimeout(resolve, 0));
  });
}
