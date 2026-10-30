import { act, render } from '@testing-library/react';
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router';
import { clearApiCache } from '@/hooks/useApi';
import { routes } from '@/routes';

/** The app's routes with every `lazy` page already loaded, so a test sees the page in the first render. */
async function loaded(list: RouteObject[]): Promise<RouteObject[]> {
  return Promise.all(list.map(async (route) => {
    const { lazy, children, ...rest } = route;
    const page = typeof lazy === 'function' ? await lazy() : {};
    return { ...rest, ...page, ...(children ? { children: await loaded(children) } : {}) } as RouteObject;
  }));
}
const eagerRoutes = await loaded(routes);

/** A fresh page load: its own router and an empty response cache. */
export function renderApp(entry = '/') {
  clearApiCache();
  const router = createMemoryRouter(eagerRoutes, { initialEntries: [entry] });
  const utils = render(<RouterProvider router={router} />);
  return { router, ...utils, params: () => new URLSearchParams(router.state.location.search) };
}

/**
 * Lets every request the page started answer and React render the answers, for a test that checks something else
 * (header, footer, a static page) and would otherwise end while the home feeds, /me or /sources are still loading.
 */
export async function settled() {
  await act(() => new Promise((resolve) => setTimeout(resolve, 0)));
}
