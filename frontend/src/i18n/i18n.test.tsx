import { screen } from '@testing-library/react';
import { makeCard, makeSearch } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

describe('i18n', () => {
  it('ships English only, with no language choice', async () => {
    mockApi();
    renderApp('/');
    await settle();
    expect(screen.queryByLabelText('Language')).not.toBeInTheDocument();
    expect(localStorage.getItem('h2.lang')).toBeNull();
    expect(document.documentElement.lang).toBe('en');
    expect(
      screen.getByRole('heading', { name: /The metasearch for the AI ecosystem/ }),
    ).toBeInTheDocument();
  });

  it('falls back to the raw slug for unknown use cases', async () => {
    mockApi({
      'GET /search': () => makeSearch({ results: [makeCard({ useCases: ['quantum-baking'] })] }),
    });
    renderApp('/search?q=x');
    expect(await screen.findByText('quantum-baking', { exact: false })).toBeInTheDocument();
  });
});
