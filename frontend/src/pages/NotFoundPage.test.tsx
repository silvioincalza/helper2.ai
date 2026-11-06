import { screen, waitFor } from '@testing-library/react';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

describe('not found page', () => {
  it('sets the tab title, says what happened and offers the home page', async () => {
    mockApi();
    document.title = 'Our mission · Helper2.ai';
    renderApp('/no/such/page');
    await screen.findByRole('heading', { level: 1, name: 'Page not found' });
    await settle();
    await waitFor(() => expect(document.title).toBe('Page not found · Helper2.ai'));
    expect(screen.getByRole('link', { name: 'Go to the home page' })).toHaveAttribute('href', '/');
  });
});
