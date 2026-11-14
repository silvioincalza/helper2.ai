import { screen, within } from '@testing-library/react';
import { ANON } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';
import { CHUNK_RELOAD_KEY } from '@/lib/staleChunk';

vi.mock('@/features/saved/SavedPage', () => {
  throw new TypeError('Failed to fetch dynamically imported module: /assets/SavedPage-old.js');
});

describe('a page that cannot open', () => {
  afterEach(() => window.sessionStorage.clear());

  it('after the one reload, says so inside the layout and offers a reload, without the stack', async () => {
    window.sessionStorage.setItem(
      CHUNK_RELOAD_KEY,
      JSON.stringify({ address: '/', at: Date.now() }),
    );
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
