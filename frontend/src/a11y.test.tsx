import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axeViolations } from '@/test/a11y';
import { ADMIN, makeDetail, makeSearch, makeStatus } from '@/test/fixtures';
import { mockApi } from '@/test/mockApi';
import { renderApp } from '@/test/render';
import { clearApiCache } from '@/hooks/useApi';

const ID = '11111111-1111-5111-8111-111111111111';

describe('a11y smoke (axe, color-contrast excluded: not computable in jsdom)', () => {
  it('the helper really detects violations', async () => {
    const div = document.createElement('div');
    div.innerHTML = '<img src="x.png"><button></button>';
    document.body.appendChild(div);
    expect((await axeViolations(div)).length).toBeGreaterThan(0);
    div.remove();
  });

  it('home', async () => {
    mockApi();
    const { container } = renderApp('/');
    await screen.findByRole('banner');
    expect(await axeViolations(container)).toEqual([]);
  });

  it('search results with expanded source table and partial banner', async () => {
    mockApi({ 'GET /search': () => makeSearch({ sources: [makeStatus(), makeStatus({ sourceId: 'x', name: 'X', outcome: 'TIMED_OUT', servedFrom: 'NONE' })], coverage: { partial: true, sourcesConfigured: 2, sourcesQueried: 2, sourcesSucceeded: 1, note: 'n' } }) });
    const { container } = renderApp('/search?q=translate&type=MODEL');
    await userEvent.click(await screen.findByRole('button', { name: /sources responded/ }));
    expect(await axeViolations(container)).toEqual([]);
  });

  it('search empty and error states', async () => {
    mockApi({ 'GET /search': () => makeSearch({ results: [], total: 0 }) });
    const a = renderApp('/search?q=zzz');
    await screen.findByText('No results from the sources searched');
    expect(await axeViolations(a.container)).toEqual([]);
    a.unmount();
    clearApiCache(); // a fresh page load: the empty answer above is not remembered
    mockApi({ 'GET /search': () => new Response('{}', { status: 500, headers: { 'content-type': 'application/json' } }) });
    const b = renderApp('/search?q=zzz');
    await screen.findByRole('alert');
    expect(await axeViolations(b.container)).toEqual([]);
  });

  it('resource detail', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail({ relationships: [{ kind: 'USES', targetTitle: 'X', targetId: null, verification: 'UNVERIFIED', evidence: [] }], assertions: [{ field: 'license', value: 'MIT', origin: 'CURATED', verification: 'VERIFIED', evidence: [] }] }) });
    const { container } = renderApp(`/resource/${ID}`);
    await screen.findByRole('heading', { name: 'Report wrong or outdated info' });
    expect(await axeViolations(container)).toEqual([]);
  });

  it('sources, admin', async () => {
    mockApi({ 'GET /me': () => ADMIN, 'GET /admin/review-items': () => [], 'GET /admin/scout-runs': () => [], 'GET /me/bookmarks': () => [] });
    const s = renderApp('/sources');
    await screen.findByRole('table');
    expect(await axeViolations(s.container)).toEqual([]);
    s.unmount();
    const ad = renderApp('/admin');
    await within(await screen.findByRole('main')).findByText('Nothing in this queue.');
    expect(await axeViolations(ad.container)).toEqual([]);
  });
});
