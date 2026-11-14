import { screen, waitFor } from '@testing-library/react';
import { makeDetail } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp, settle } from '@/test/render';

const ID = '11111111-1111-5111-8111-111111111111';
const robots = () => document.head.querySelector('meta[name="robots"]')?.getAttribute('content');

describe('Feature: search engines index only the pages worth finding (SEO review F3, F8)', () => {
  it('Scenario: an unknown page says noindex', async () => {
    mockApi();
    renderApp('/nope');
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument();
    expect(robots()).toBe('noindex');
  });

  it('Scenario: a resource that does not exist says noindex', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => problem(404, 'Not found') });
    renderApp(`/resource/${ID}`);
    expect(await screen.findByText('Resource not found')).toBeInTheDocument();
    expect(robots()).toBe('noindex');
  });

  it('Scenario: a resource that exists has no robots tag', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail() });
    renderApp(`/resource/${ID}`);
    expect(await screen.findByRole('heading', { level: 1, name: 'Acme Translator' })).toBeVisible();
    expect(robots()).toBeUndefined();
  });

  it('Scenario: a resource that failed with a server error keeps no robots tag (it may load next time)', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => problem(502, 'Bad gateway') });
    renderApp(`/resource/${ID}`);
    expect(await screen.findByRole('button', { name: /Try again/ })).toBeInTheDocument();
    expect(robots()).toBeUndefined();
  });

  it.each(['/search?q=mcp', '/?q=mcp', '/saved', '/account'])(
    'Scenario: %s is not indexed, but its links are followed',
    async (path) => {
      mockApi();
      renderApp(path);
      await settle();
      await waitFor(() => expect(robots()).toBe('noindex, follow'));
    },
  );

  it('Scenario: the home page has no robots tag', async () => {
    mockApi();
    renderApp('/');
    await settle();
    expect(robots()).toBeUndefined();
  });
});
