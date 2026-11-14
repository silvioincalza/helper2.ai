import { screen } from '@testing-library/react';
import { makeCard, makeDetail } from '@/test/fixtures';
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
      screen.getByRole('heading', { name: /The metasearch for the AI universe/ }),
    ).toBeInTheDocument();
  });

  it('falls back to the raw slug for unknown use cases', async () => {
    const card = makeCard({ useCases: ['quantum-baking'] });
    mockApi({ [`GET /resources/${card.id}`]: () => makeDetail({ card }) });
    renderApp(`/resource/${card.id}`);
    expect((await screen.findAllByText('quantum-baking', { exact: false }))[0]).toBeInTheDocument();
  });
});
