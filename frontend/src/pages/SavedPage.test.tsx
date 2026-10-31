import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Bookmark } from '@/api/types';
import { readLocalBookmarks, writeLocalBookmarks } from '@/lib/bookmarks';
import { axeViolations } from '@/test/a11y';
import { USER, makeDetail } from '@/test/fixtures';
import { mockApi, problem } from '@/test/mockApi';
import { renderApp } from '@/test/render';

const ID = '11111111-1111-5111-8111-111111111111';
const local: Bookmark = { resourceId: '22222222-2222-5222-8222-222222222222', title: 'Local one', url: 'https://l.example/x', type: 'MODEL', savedAt: '2026-10-01T00:00:00.000Z' };
const other: Bookmark = { resourceId: '33333333-3333-5333-8333-333333333333', title: 'Server only', url: 'https://s.example', type: null, savedAt: '2026-10-21T00:00:00Z' };

describe('Feature: save resources (guest, in this browser)', () => {
  it('Scenario: a guest saves a result, finds it on Saved and removes it with the keyboard', async () => {
    // Given a guest on the results page
    mockApi();
    const { router, container } = renderApp('/search?q=translate');
    // When they save the selected result from its preview
    const save = await screen.findByRole('button', { name: 'Save Acme Translator' });
    await userEvent.click(save);
    expect(save).toHaveAttribute('aria-pressed', 'true');
    expect(readLocalBookmarks()[0]).toMatchObject({ title: 'Acme Translator', type: 'PRODUCT' });
    // And open Saved from the header
    await userEvent.click(within(screen.getByRole('navigation', { name: 'Main' })).getByRole('link', { name: 'Saved' }));
    expect(router.state.location.pathname).toBe('/saved');
    // Then the page says where the list lives and shows the resource with its type, site and link
    expect(await screen.findByRole('heading', { level: 1, name: 'Saved resources' })).toHaveFocus();
    expect(screen.getByText('Saved in this browser only.')).toBeInTheDocument();
    const row = within(screen.getByRole('list', { name: 'Saved resources' })).getByRole('listitem');
    expect(within(row).getByRole('link', { name: 'Acme Translator' })).toHaveAttribute('href', `/resource/${ID}`);
    expect(within(row).getByText('Product')).toBeInTheDocument();
    expect(within(row).getByRole('link', { name: /Open on source/ })).toHaveAttribute('href', 'https://www.acme.example/translator');
    expect(await axeViolations(container)).toEqual([]);
    // When they remove it by keyboard
    within(row).getByRole('button', { name: 'Remove Acme Translator from saved' }).focus();
    await userEvent.keyboard('{Enter}');
    // Then the list is empty, focus is on the empty state's heading, and a screen reader hears what happened
    expect(screen.getByRole('heading', { name: 'Nothing saved yet' })).toHaveFocus();
    expect(screen.getByRole('status')).toHaveTextContent('Removed Acme Translator from saved.');
    expect(readLocalBookmarks()).toEqual([]);
  });

  it('Scenario: removing one of several moves focus to the Remove button of the row that takes its place', async () => {
    writeLocalBookmarks([local, other]);
    mockApi();
    renderApp('/saved');
    await userEvent.click(await screen.findByRole('button', { name: 'Remove Local one from saved' }));
    expect(screen.getByRole('button', { name: 'Remove Server only from saved' })).toHaveFocus();
    expect(screen.queryByRole('link', { name: 'Local one' })).not.toBeInTheDocument();
  });

  it('Scenario: nothing saved yet; the next step is a search', async () => {
    mockApi();
    const { container } = renderApp('/saved');
    expect(await screen.findByRole('heading', { name: 'Nothing saved yet' })).toBeInTheDocument();
    expect(screen.getByText('Use Save on any result to keep it here.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Search' })).toHaveAttribute('href', '/');
    expect(await axeViolations(container)).toEqual([]);
  });

  it('Scenario: the browser blocks storage; saving still works on this page and the page warns', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota'); });
    mockApi();
    const { router } = renderApp('/search?q=translate');
    await userEvent.click(await screen.findByRole('button', { name: 'Save Acme Translator' }));
    expect(screen.getByRole('button', { name: 'Save Acme Translator' })).toHaveAttribute('aria-pressed', 'true');
    await act(() => router.navigate('/saved'));
    expect(await screen.findByRole('alert')).toHaveTextContent('Your browser blocked storage');
    expect(screen.getByRole('link', { name: 'Acme Translator' })).toBeInTheDocument();
  });

  it('Scenario: the resource page saves and unsaves', async () => {
    mockApi({ [`GET /resources/${ID}`]: () => makeDetail() });
    renderApp(`/resource/${ID}`);
    const save = await screen.findByRole('button', { name: 'Save Acme Translator' });
    await userEvent.click(save);
    expect(save).toHaveAttribute('aria-pressed', 'true');
    await userEvent.click(save);
    expect(save).toHaveAttribute('aria-pressed', 'false');
    expect(readLocalBookmarks()).toEqual([]);
  });
});

describe('Feature: save resources (signed in, on the account)', () => {
  it('Scenario: after sign-in the browser list joins the account once, then the account list is shown', async () => {
    // Given a bookmark in this browser and a signed-in user
    writeLocalBookmarks([local]);
    const server = [{ ...local, savedAt: '2026-10-22T00:00:00Z' }, other];
    const m = mockApi({ 'GET /me': () => USER, 'POST /me/bookmarks': () => server });
    const { router } = renderApp('/saved');
    // Then the account's list shows both, and the page says they are on the account
    expect(await screen.findByRole('link', { name: 'Server only' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Local one' })).toBeInTheDocument();
    expect(screen.getByText('Saved on your account.')).toBeInTheDocument();
    // And the browser's list was sent once and cleared
    const posts = m.callsTo('POST', '/me/bookmarks');
    expect(posts).toHaveLength(1);
    expect(posts[0]?.body).toEqual([{ resourceId: local.resourceId, title: 'Local one', url: local.url, type: 'MODEL' }]);
    await waitFor(() => expect(readLocalBookmarks()).toEqual([]));
    // When they leave and come back, nothing is merged again
    await act(() => router.navigate('/sources'));
    await act(() => router.navigate('/saved'));
    await screen.findByRole('link', { name: 'Server only' });
    expect(m.callsTo('POST', '/me/bookmarks')).toHaveLength(1);
  });

  it('Scenario: while the account list loads, the page says so instead of "Nothing saved yet"', async () => {
    mockApi({ 'GET /me': () => USER, 'GET /me/bookmarks': () => new Promise(() => undefined) });
    renderApp('/saved');
    expect(await screen.findByText('Loading…')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Nothing saved yet' })).not.toBeInTheDocument();
  });

  it('Scenario: the account list cannot load; Try again keeps focus on the page and shows the list', async () => {
    let fail = true;
    const m = mockApi({ 'GET /me': () => USER, 'GET /me/bookmarks': () => (fail ? problem(503, 'Service Unavailable') : [other]) });
    const { container } = renderApp('/saved');
    const alert = await screen.findByRole('alert');
    expect(within(alert).getByRole('heading', { name: 'Could not load your saved resources' })).toBeInTheDocument();
    expect(await axeViolations(container)).toEqual([]);
    fail = false;
    within(alert).getByRole('button', { name: 'Try again' }).focus();
    await userEvent.keyboard('{Enter}');
    expect(await screen.findByRole('link', { name: 'Server only' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).toHaveFocus());
    expect(m.callsTo('GET', '/me/bookmarks')).toHaveLength(2);
  });

  it('Scenario: removing deletes it from the account', async () => {
    const m = mockApi({ 'GET /me': () => USER, 'GET /me/bookmarks': () => [other], 'DELETE /me/bookmarks/*': () => undefined });
    renderApp('/saved');
    await userEvent.click(await screen.findByRole('button', { name: 'Remove Server only from saved' }));
    await waitFor(() => expect(m.callsTo('DELETE', `/me/bookmarks/${other.resourceId}`)).toHaveLength(1));
    expect(screen.getByRole('heading', { name: 'Nothing saved yet' })).toBeInTheDocument();
  });

  it('Scenario: the account refuses a removal; the item comes back and the page says so', async () => {
    mockApi({ 'GET /me': () => USER, 'GET /me/bookmarks': () => [other], 'DELETE /me/bookmarks/*': () => problem(503, 'Service Unavailable') });
    renderApp('/saved');
    await userEvent.click(await screen.findByRole('button', { name: 'Remove Server only from saved' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not update your account');
    expect(screen.getByRole('link', { name: 'Server only' })).toBeInTheDocument();
  });
});
