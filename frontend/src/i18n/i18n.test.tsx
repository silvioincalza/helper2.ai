import { screen } from '@testing-library/react';
import { LANGUAGES } from '.';
import { makeCard, makeSearch } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp, settled } from '@/test/render';

describe('i18n', () => {
  it('ships English only and hides the language switcher while there is one language', async () => {
    mockApi();
    renderApp('/');
    await settled();
    expect(LANGUAGES.map((l) => l.code)).toEqual(['en']);
    expect(screen.queryByLabelText('Language')).not.toBeInTheDocument();
    expect(document.documentElement.lang).toBe('en');
    expect(screen.getByRole('heading', { name: /Describe what you need/ })).toBeInTheDocument();
  });

  it('falls back to the raw slug for unknown use cases', async () => {
    mockApi({ 'GET /search': () => makeSearch({ results: [makeCard({ useCases: ['quantum-baking'] })] }) });
    renderApp('/search?q=x');
    expect(await screen.findByText('quantum-baking', { exact: false })).toBeInTheDocument();
  });

});
