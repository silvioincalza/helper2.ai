import { screen, within } from '@testing-library/react';
import { ANON } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';
import { CHUNK_RELOAD_KEY } from '@/lib/staleChunk';

// The file of an older release is gone: the import of the saved page fails.
vi.mock('@/pages/SavedPage', () => {
  throw new TypeError('Failed to fetch dynamically imported module: /assets/SavedPage-old.js');
});

describe('a page that cannot open', () => {
  afterEach(() => window.sessionStorage.clear());

  it('after the one reload, says so inside the layout and offers a reload, without the stack', async () => {
    // The tab already reloaded for this address (the memory router leaves window.location on "/").
    window.sessionStorage.setItem(CHUNK_RELOAD_KEY, '/');
    mockApi({ 'GET /me': () => ANON });
    renderApp('/saved');

    const alert = await screen.findByRole('alert');
    expect(
      within(alert).getByRole('heading', { name: 'This page could not open' }),
    ).toBeInTheDocument();
    expect(within(alert).getByRole('button', { name: 'Reload the page' })).toBeInTheDocument();
    expect(document.querySelector('header.site-header')).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('Unexpected Application Error');
    expect(document.body).not.toHaveTextContent('Failed to fetch');
    await settle();
  });
});
